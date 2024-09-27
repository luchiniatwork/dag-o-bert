(ns user
  (:require [weavejester.dependency :as dep]))

(def dag {:nodes {:a nil
                  :b nil
                  :c nil
                  :d nil}
          :edges [[:a :b]
                  [:a :c]
                  [:b :d]
                  [:c :b]
                  [:c :d]]})

(defn ^:private next-nodes [{:keys [edges] :as dag} from-node]
  (->> edges
       (filter #(= from-node (first %)))
       (map second)))

(defn ^:private prev-nodes [{:keys [edges] :as dag} to-node]
  (->> edges
       (filter #(= to-node (second %)))
       (map first)))

(let [graph (reduce (fn [g [node _]]
                      (reduce #(dep/depend %1 node %2) g (prev-nodes dag node)))
                    (dep/graph)
                    (:nodes dag))]
  (dep/topo-sort graph))


(cljc.java-time.instant)
