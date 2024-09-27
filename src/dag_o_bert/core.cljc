(ns dag-o-bert.core
  (:require [cljc.java-time.instant :as inst]
            [clojure.core.async :refer [go go-loop chan <! >! <!! >!! close!] :as async]
            [weavejester.dependency :as dep]))


(defn ^:private nano-id []
  (let [f (fn [c start]
            (->> (range 0 c)
                 (map #(+ start %))
                 (map char)
                 (apply str)))
        dict (str (f 26 65) ;; [A-Z]
                  (f 26 97) ;; [a-z]
                  (f 10 48) ;; [0-9]
                  "-_")]
    (->> (range 0 21)
         (map (fn [_] (rand-nth dict)))
         (apply str))))


(defn ^:private prev-edges [{:keys [edges] :as dag} to-node]
  (->> edges
       (filter #(= to-node (second %)))))


(defn ^:private prev-nodes [{:keys [edges] :as dag} to-node]
  (map first
       (prev-edges dag to-node)))


(defn ^:private create-control [{:keys [nodes edges] :as dag}]
  (let [graph (reduce (fn [g [node _]]
                        (reduce #(dep/depend %1 node %2) g (prev-nodes dag node)))
                      (dep/graph)
                      (:nodes dag))
        sorted-nodes (dep/topo-sort graph)]
    (->> sorted-nodes
         (reduce (fn [c node]
                   (conj c [node (prev-edges dag node)]))
                 []))))


(defn ^:private call-throwable [f input]
  (try
    [:done (f input)]
    (catch Throwable ex
      [:failed ex])))


(defn ^:private run-wo-deps [{:keys [observer] :as _opts}
                             in out node node-f]
  (go (let [start-request (inst/to-epoch-milli (inst/now))
            [run-ctx input] (<! in)
            run-id (:run-id run-ctx)
            start-execution (inst/to-epoch-milli (inst/now))
            [status ret] (call-throwable node-f input)
            end-execution (inst/to-epoch-milli (inst/now))
            new-run-ctx (cond-> run-ctx
                          (= :failed  status)
                          (assoc :control :abort
                                 :ex ret))]
        (>! out [node new-run-ctx ret])
        (close! in)
        (close! out)
        (when observer
          (go (observer {:run-id run-id
                         :node node
                         :start-request start-request
                         :waiting-ms (- start-execution start-request)
                         :start-execution start-execution
                         :end-execution end-execution
                         :elapsed-execution-ms (- end-execution start-execution)
                         :elapsed-total-ms (- end-execution start-request)
                         :input input
                         :status status
                         :return ret}))))))


(defn ^:private get-edge-opts [edge-coll from-node to-node]
  (let [edge (some->> edge-coll
                      (filter #(and (= from-node (first %))
                                    (= to-node (second %))))
                      first)
        [_ _ edge-opts] edge]
    edge-opts))


(defn ^:private run-with-deps [{:keys [observer] :as _opts}
                               in out node node-f prev-edges]
  (go (let [start-request (inst/to-epoch-milli (inst/now))
            input-map (<! (async/reduce (fn [m [from-node run-ctx node-ret]]
                                          (let [{:keys [transform] :as edge-opts}
                                                (get-edge-opts prev-edges from-node node)
                                                pred (or (:filter edge-opts)
                                                         (constantly true))
                                                k (or (:name edge-opts) from-node)
                                                node-ret' (if transform
                                                            (transform node-ret)
                                                            node-ret)]
                                            (cond-> m
                                              (= :abort (:control run-ctx))
                                              (assoc ::must-skip? true)
                                              (pred node-ret')
                                              (assoc k node-ret'
                                                     ::run-ctx run-ctx))))
                                        {}
                                        in))
            run-ctx (::run-ctx input-map)
            must-skip? (::must-skip? input-map)
            input-proper (dissoc input-map ::run-ctx ::must-skip?)
            start-execution (inst/to-epoch-milli (inst/now))
            [status ret] (when (not must-skip?)
                           (call-throwable node-f input-proper))
            end-execution (inst/to-epoch-milli (inst/now))
            new-run-ctx (cond-> run-ctx
                          (= :failed  status)
                          (assoc :control :abort
                                 :ex ret))
            ret-pack [node new-run-ctx ret]]
        (>! out ret-pack)
        (close! out)
        (when observer
          (go (observer {:run-id (:run-id run-ctx)
                         :node node
                         :start-request start-request
                         :waiting-ms (- start-execution start-request)
                         :start-execution start-execution
                         :end-execution end-execution
                         :elapsed-execution-ms (- end-execution start-execution)
                         :elapsed-total-ms (- end-execution start-request)
                         :input input-proper
                         :status (or status :skipped)
                         :return ret}))))))


(defn ^:private create-chans [{:keys [nodes start-node end-node]} opts control]
  (let [chans (->> control
                   (reduce (fn [m [node prev-edges]]
                             (let [prev-ns (map first prev-edges)
                                   node-f (get nodes node)
                                   tapped-ins (reduce (fn [m prev-n]
                                                        (assoc m prev-n (chan)))
                                                      {}
                                                      prev-ns)
                                   in (if (= 0 (count tapped-ins))
                                        (chan)
                                        (async/merge (vals tapped-ins)))
                                   out (chan)
                                   mult (when (not= end-node node)
                                          (async/mult out))]
                               (doseq [[prev-n _ edge-opts] prev-edges]
                                 (async/tap (get-in m [prev-n :mult]) (get tapped-ins prev-n)))
                               (if (= 0 (count tapped-ins))
                                 (run-wo-deps opts in out node node-f)
                                 (run-with-deps opts in out node node-f prev-edges))
                               (-> m
                                   (assoc-in [node :in] in)
                                   (assoc-in [node :out] out)
                                   (assoc-in [node :mult] mult))))
                           {}))]
    [(get-in chans [start-node :in])
     (get-in chans [end-node :out])]))


(defn run
  ([dag payload]
   (run dag nil payload))
  ([{:keys [nodes edges start-node end-node] :as dag} {:keys [observer] :as opts} payload]
   (go (let [start-request (inst/to-epoch-milli (inst/now))
             control (create-control dag)
             [in out] (create-chans dag opts control)
             start-execution (inst/to-epoch-milli (inst/now))
             run-ctx {:run-id (nano-id)}]
         (>! in [run-ctx payload])
         (let [[_ latest-run-ctx ret] (<! out)
               end-execution (inst/to-epoch-milli (inst/now))
               return-run-ctx (-> latest-run-ctx
                                  (assoc :start-request start-request
                                         :graph-overhead-ms (- start-execution start-request)
                                         :start-execution start-execution
                                         :end-execution end-execution
                                         :elapsed-execution-ms (- end-execution start-execution)
                                         :elapsed-total-ms (- end-execution start-request)))]
           [return-run-ctx ret])))))


(defn run-sync
  ([dag payload]
   (run-sync dag nil payload))
  ([dag opts payload]
   (let [[run-ctx ret] (<!! (run dag opts payload))]
     (if (= :abort (:control run-ctx))
       (throw (ex-info "Execution aborted due to exception" {:ex (:ex run-ctx)}))
       ret))))


(comment
  (let [observer (fn [i]
                   #_(clojure.pprint/pprint i)
                   (println "node" (:node i)
                            "status" (:status i)
                            "waited" (:waiting-ms i) "ms"
                            "and took" (:elapsed-execution-ms i) "ms"
                            "total elapsed" (:elapsed-total-ms i) "ms"))
        payload 3
        dag {:nodes {:a (fn [i] (println "called :a with" i)
                          (identity i)
                          #_(throw (ex-info "foo" {})))
                     :d (fn [i]
                          (println "called :d with" i)
                          (assoc i :d :done))
                     :b (fn [i]
                          (Thread/sleep 600)
                          (println "called :b with" i)
                          (-> i :a inc))
                     :c (fn [i]
                          (Thread/sleep 800)
                          (println "called :c with" i)
                          (-> i :a dec))}
             :edges [[:a :b]
                     [:a :c]
                     [:b :d]
                     [:c :d]]
             :start-node :a
             :end-node :d}]
    (run-sync dag {:observer observer} payload)))


(create-control {:nodes {:a nil
                         :b nil
                         :c nil}
                 :edges [[:a :b]
                         [:a :b]
                         [:b :c]]})
