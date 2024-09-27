# DAG-o-bert

[![Clojars][clojars-badge]][clojars]
[![License][license-badge]][license]
![Status][status-badge]

`DAG-o-bert` is an async DAG execution library for Clojure and
ClojureScript no-one asked for.

The idea behind `DAG-o-bert` is pretty simple: you provide a DAG with
a series of potentially slow and exception-throwing functions and
`DAG-o-bert` takes the heavy lifting of calling each function with the
correct dependency set, with maximum parallelism, and with error
propagation/abortion.

Key features:

- maximum parallelism
- error propagation (see [Exceptions and abortion
  propagation](#exceptions-and-abortion-propagation))
- graphs are just data and can be created any way you want (even
  [dynamically](#dynamic-graphs).)
- runs on top of [Clojure core async][core-async] but hiding its
  complexities
- fits nicely under some [useful utilization
  patterns](#useful-patterns)

More context under [Motivation](#motivation).

## Installation

Include the following dependency in your `deps.edn`:

```clojure
{:deps {net.clojars.luchiniatwork/dag-o-bert {:mvn/version "0.1.0"}}}
```


## Basic Usage

Let's say you have two slow functions that can run in parallel
(`:slow-inc` and `:slow-dec` below.) The merging function (defined by
node `:end` in this example) takes the results of both dependent edges
and is the final return of the DAG (defined by `:end-node`).

```clojure
(require '[dag-o-bert.core :as [bert]])

(let [dag {:nodes {:start identity
                   :slow-inc (fn [{:keys [start]}]
                               (Thread/sleep 600)
                               (inc start))
                   :slow-dec (fn [{:keys [start]}]
                               (Thread/sleep 800)
                               (dec start))
                   :end (fn [{:keys [slow-inc slow-dec]}]
                          (* slow-inc slow-dec))}
           :edges [[:start :slow-dec]
                   [:start :slow-inc]
                   [:slow-inc :end]
                   [:slow-dec :end]]
           :start-node :start
           :end-node :end}]
  (bert/run-sync dag 3)) ;; => 8 (after about 800ms)
```

## Graph spec

`DAG-o-bert` is designed to be simple to use. The graph definition
takes a map with the following fields:

- `:nodes` - a map where each node is keyed by its internal reference
  name and the value is a function that receives a single payload
  parameter
- `:edges` - a list of tuples in the format `[:from-node
  :to-node]`. Edge tuples can have a third entry that refines the
  [edge settings](#advanced-edge-settings).
- `:start-node` - the name of the node that starts the graph. This
  node is the one that will received the payload (unaltered) through
  `bert/run` or `bert/run-sync`
- `:end-node` - the name of the node that will be the last one in the
  graph. In practice, this is the node that will return at the end of
  `bert/run` or `bert/run-sync`. [Dangling nodes][#dangling-nodes] are
  also possible but an end node is mandatory.


## Async and sync versions

`DAG-o-bert` uses [core async][core-async] under the hood. The
function `bert/run` returns a channel that will have one entry and
immediately close when the execution of the graph is completed.

The single entry looks like this (assuming the dag from the previous
example):

```clojure
(<!! (bert/run dag 3)) ;; => [{:run-id "G34sa..."} 8]
```

The first entry in the tuple is a map that collects the running
context (in the above case, a unique `:run-id` is shown but you can
find other useful metadata there - i.e. `:elapsed-total-ms` shows the
total elapsed time).

The second entry in the tuple is the return of the graph proper.

The synchronous version of `bert/run` is `bert/run-sync` and it does
not return the metadata for the running context.

See [Exceptions and abortion
propagation](#exceptions-and-abortion-propagation) for more details on
the running context.


## Exceptions and abortion propagation

If any of the functions in the graph throw an exception, an abortion
propagation flow is triggered. This means that any downstream function
gets skipped.

When using the async version `bert/run` (see [Async and sync
versions](#async-and-sync-versions) the running context will contain
the marker `:control` with `:abort` and details about the exception at
`:ex` in the running context map. Ie:

```clojure
(<!! (bert/run dag 3)) ;; => [{:run-id "G34sa..." :control :abort :ex <exception obj>} nil]
```

The sync version `bert/run-sync` will throw instead.


## Observing execution flows

An observing function can be provided to inspect/log (or trigger other
ephemeral side-effects) during the execution flow.

Both `bert/run` and `bert/run-sync` can have the second parameter as
an options map:

```clojure
(bert/run-sync dag {:observer println} 3)
```

Please notice, that the observer is run on an async channel so
synchronous functions such as `println` probably do not yield the best
results.


## Advanced edge settings

Edge tuples can take a third entry for advanced settings. I.e the
following example will place the output of `:from-node` as key
`:my-key` when calling node `:to-hode`.

```clojure
[:from-node :to-node {:name :my-key}
```

### :name

The default behavior for downstream nodes is to key the payload map
with the name of the previous node. I.e. if the edge is `[:from-node
:to-node]` then `:to-node` will receive the value of `:from-node`
under the key named exactly `:from0node`.

Sometimes this behavior is not desired. `:name` allows you to define
the name of key to be used. I.e. `[:from-node :to-node {:name
:my-key-name}]`

### :transform

`:transform` lets you define a transformation function that will be
called with the output from the previous node before being applied to
the input map of the receiving node.

This is usually handy for simple mapping functions or data type
converstions and should be used sparingly because debugging
transformers can be tricky. They are not as controlled as the possible
[more complex alternative] of creatign an intermediary transformation
node.

Example:

```clojure
(let [g {:nodes {:a identity
                 :b identity}
         :edges [[:a :b {:transform str}]]
         :start-node :a
         :end-node :b}]
  (bert/run-sync g 5)) ;; => {:a "5"}
```

### :filter

`:filter` lets you define a predicate function that will be called
_after_ transform but _before_ sending the return downstream. If this
function returns truthy, then the return is added to the map that will
be passed down downstream otherwise the return is not added.

This is usually useful if you know that some upstream functions can
return things you don't need downstream. These functions are not as
controlled node functions so you should make sure they are pretty
simple, safe, pure, etc.

Example:

```clojure
(let [g {:nodes {:a identity
                 :b #(:a %)}
         :edges [[:a :b {:filter odd?}]]
         :start-node :a
         :end-node :b}]
  (bert/run-sync g 1) ;; => 1
  (bert/run-sync g 2) ;; => nil
  )
```


## Dynamic graphs

Because graphs are simple data structures you can create them
dynamically as needed. This might be useful if you have other sources
for your graph (i.e. a database or some other externality.)

Here's a rather convoluted example from the tests:

```clojure
(let [r (range 20)
      g {:nodes (merge {:a identity
                        :c #(->> (vals %)
                                 (apply +))}
                       (reduce (fn [m i]
                                 (assoc m (keyword (str i))
                                        #(+ (:a %) i)))
                               {} r))
         :edges (->> r
                     (reduce (fn [c i]
                               (conj c
                                     [:a (keyword (str i))]
                                     [(keyword (str i)) :c]))
                             []))
         :start-node :a
         :end-node :c}]
  (bert/run-sync g 1)) ;; => 210
```

## Useful patterns

`DAG-o-bert` takes the graph (and optionally also an options
parameter) up front in order to facilitate using it with `partial`. I.e.:

```clojure
(def my-graph-fn (partial bert/run-sync my-graph))

(my-graph 42) ;; => yields whatever your graph yields
```

Adding execution options wouldn't affect your main interface:

```clojure
(def my-graph-fn (partial bert/run-sync my-graph {:observer performance-logger))

(my-graph 42) ;; => yields whatever your graph yields (and also run your performance-logger asynchronously)
```

This is particularly useful if combined with your dependency injection
system. For instance, a hypothetical example for
[Integrant][integrant] could look like the method below. It would
return a synchronous function capable of coordinating the graph
execution and prorperly injected with all external
dependencies. Everything with simple data and functions!

```clojure
(defmethod ig/init-key ::my-graph [_ {:keys [db-conn
                                             llm-conn]}]
  (let [graph {:nodes {:start fn-that-usees-llm-conn
                       :chained fn-that-follows-start-and-does-other-llm-stuff
                       :dangling fn-that-saves-something-on-db
                       :end fn-that-does-more-llm-stuff}
               :edges [[:start :chained]
                       [:chained :dangling]
                       [:chained :end]]
               :start-node :start
               :end-node :end}]
    (partial bert/run-sync)))
```


## Dangling nodes


## Caveats

Async programming has its caveats so you should be prepared for some
things.

For instance, observers are put on async channels to make sure they do
not block/delay the execution of the flow. This can be confusing if
you are trying to log to the console without some sort of transactor.

Despite being async, observers are still potentially consuming other
shared resources (i.e. disk, memory, network), so running costly
observers at scale will require scaling your machine resources
accordingly.

`DAG-o-bert` does not aim to optimize memory consumption when
calling node functions. It simply trusts that whatever you returned is
going to be relevant downstream. If you have big data structures and
big graphs, you will need to scale your machine memory accordingly.

Lastly, there is a small overhead on each call to `bert/run` and
`bert/run-sync` to validate the graph and prepare the required
channels. It is usually neglegible but you should not use `DAG-o-bert`
if your async flows and/or error handling scenarios are simple.


## Development

Tests can be run with:

``` bash
$ clojure -M:test --watch
```


## Motivation

Async programming is hard and everyone and their grandmas tried to
solve it one way or another. I am a huge fan of [Clojure core
async][core-async]. Despite criticism from some, I find it pretty
natural to use but it can get complicated and hard to debug as flows
grow.

When developing AI agents and chains I noticed certain patterns that I
kept using over and over again. All these LLM foundational models,
vector databases, execution tools, etc are pretty slow and error-prone
calls that need to be coordinated within each execution flow.

`DAG-o-bert` came out of a desire to "keep it simple": a simple DAG
that "does it all" for me in these constantly-evolving and failing
cases.

`DAG-o-bert` does not reinvent any wheel and simply sits on top of
reusable patterns. Additionally, the sync version may even be
appreaciated for debugging and/or developers who would rather plug
slow calls within different paradigms.

`DAG-o-bert` is "just data": create valid graph with a map of
`:nodes`, a set of `:edges`, a `:start-node` and an `:end-node` and
you are good to go.

Why the name `DAG-o-bert`? Because I was tired and couldn't think of
anything better at the time. :)


## License

Distributed under the MIT Public License. Use it as you
will. Contribute if you have the time.

<!-- links -->

[core-async]: #
[integrant]: #

[license-badge]: https://img.shields.io/badge/license-MIT-blue.svg
[license]: #license

[clojars-badge]: https://img.shields.io/clojars/v/luchiniatwork/dag-o-bert.svg
[clojars]: http://clojars.org/luchiniatwork/dag-o-bert

[status-badge]: https://img.shields.io/badge/project%20status-prod-brightgreen.svg
