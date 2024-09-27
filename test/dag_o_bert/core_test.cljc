(ns dag-o-bert.core-test
  (:require [cljc.java-time.instant :as inst]
            [clojure.core.async :refer [<! >! <!! >!! go go-loop chan timeout]]
            [clojure.test :refer [testing is deftest]]
            [dag-o-bert.core :refer [run run-sync]]))


(def base-dag {:edges [[:a :b]
                       [:a :c]
                       [:b :d]
                       [:b :dangling]
                       [:c :d]]
               :start-node :a
               :end-node :d})


(defn inc-from-a [{:keys [a]}]
  (inc a))


(defn dec-from-a [{:keys [a]}]
  (dec a))


(defn slow-inc [x]
  (Thread/sleep 500)
  (inc-from-a x))


(defn slow-dec [x]
  (Thread/sleep 200)
  (dec-from-a x))

(defn dag [nodes]
  (assoc base-dag :nodes nodes))


(deftest basic-tests

  (testing "simple happy case"
    (let [g (dag {:a identity
                  :b inc-from-a
                  :c dec-from-a
                  :d (fn [{:keys [b c]}] (* b c))})]
      (is (= 8 (run-sync g 3)))
      (is (= 15 (last (<!! (run g 4)))))
      (is (= #{:run-id
               :start-request
               :graph-overhead-ms
               :start-execution
               :elapsed-execution-ms
               :end-execution
               :elapsed-total-ms}
             (set (keys (first (<!! (run g 4)))))))))

  (testing "parallel nodes are slow"
    (let [g (dag {:a identity
                  :b slow-inc
                  :c slow-dec
                  :d (fn [{:keys [b c]}] (* 2 b c))})]
      (let [start (inst/to-epoch-milli (inst/now))
            ret (run-sync g 3)
            end (inst/to-epoch-milli (inst/now))]
        (is (= 16 ret))
        (is (= 5 (int (/ (- end start) 100)))) ;;should be around 500ms and no more
        )
      (let [start (inst/to-epoch-milli (inst/now))
            ret (last (<!! (run g 4)))
            end (inst/to-epoch-milli (inst/now))]
        (is (= 30 ret))
        (is (= 5 (int (/ (- end start) 100)))) ;;should be around 500ms and no more
        )))

  (testing "dangling should be called"
    (let [!control (atom nil)
          g (dag {:a identity
                  :b inc-from-a
                  :c dec-from-a
                  :dangling (fn [{:keys [b]}] (reset! !control :ok))
                  :d (fn [{:keys [b c]}] (* b c))})]
      (run-sync g 6)
      (is (= :ok @!control))))

  (testing "slow dangling should be called"
    (let [!control (atom nil)
          g (dag {:a identity
                  :b inc-from-a
                  :c dec-from-a
                  :dangling (fn [{:keys [b]}]
                              (Thread/sleep 200)
                              (reset! !control :ok))
                  :d (fn [{:keys [b c]}] (* b c))})]
      (run-sync g 6)
      (is (= nil @!control))
      (Thread/sleep 250) ;; dangling will eventually run but we assume
      ;; it's not part of the dep flow
      (is (= :ok @!control))))

  (testing "dynamic high-parallel"
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
      (is (= 190 (run-sync g 0)))
      (is (= 210 (run-sync g 1))))))


(deftest exception-tests

  (testing "should propagate abortion"
    (let [!control (atom nil)
          g (dag {:a (fn [_] (throw (ex-info "foobar" {})))
                  :b (fn [_] (reset! !control :called))
                  :c (fn [_] (reset! !control :called))
                  :d (fn [_] (reset! !control :called))})
          [ctx ret] (<!! (run g 42))]
      (is (= nil ret))
      (is (= nil @!control))
      (is (= :abort (:control ctx)))
      (is (= "foobar" (-> ctx :ex .getMessage)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Execution aborted due to exception"
                            (run-sync g 42)))))

  (testing "should propagate abortion - more complex case"
    (let [!control (atom {})
          g (dag {:a identity
                  :b (fn [_] (swap! !control assoc :b :ok))
                  :dangling (fn [_] (swap! !control assoc :dangling :ok))
                  :c (fn [_] (throw (ex-info "foobar" {})))
                  :d (fn [_] (swap! !control assoc :d :ok))})
          [ctx ret] (<!! (run g 42))]
      (is (= nil ret))
      (Thread/sleep 100) ;; Yeah: ugly but needed
      (is (= nil (:d @!control)))
      (is (= :ok (:b @!control)))
      (is (= :ok (:dangling @!control)))
      (is (= :abort (:control ctx)))
      (is (= "foobar" (-> ctx :ex .getMessage))))))


(deftest observers
  (let [!control (atom {})
        g (dag {:a identity
                :b inc-from-a
                :c dec-from-a
                :d (fn [{:keys [b c]}] (* b c))})
        observer (fn [{:keys [node status input return]}]
                   (swap! !control assoc node
                          {:status status
                           :input input
                           :return return}))]
    (run-sync g {:observer observer} 3)
    (Thread/sleep 100) ;; Yeah: this is ugly but observers run async
    ;; so, give them a break
    (is (= {:a {:input 3, :return 3, :status :done},
            :b {:input {:a 3}, :return 4, :status :done},
            :c {:input {:a 3}, :return 2, :status :done},
            :d {:input {:b 4, :c 2}, :return 8, :status :done}}
           @!control))))


#_(deftest basic-validations
    (testing "should have correct :nodes")
    (testing "should have correct :edges")
    (testing "should have correct :start-node")
    (testing "should have correct :end-node")
    (testing ":start-node should have no deps")
    (testing "there shouldn't be more than one edge to the same nodes")
    (testing "no cycclical graphs"))


(deftest edge-opts
  (testing "named edges"
    (let [g {:nodes {:a identity
                     :b (fn [{:keys [n1]}]
                          (* 2 n1))
                     :c (fn [{:keys [n2 n3]}]
                          (+ n2 n3))}
             :edges [[:a :b {:name :n1}]
                     [:a :c {:name :n2}]
                     [:b :c {:name :n3}]]
             :start-node :a
             :end-node :c}]
      (is (= 15 (run-sync g 5)))))

  (testing "transfrom edges"
    (let [g {:nodes {:a identity
                     :b #(:a %)}
             :edges [[:a :b {:transform str}]]
             :start-node :a
             :end-node :b}]
      (is (= "5" (run-sync g 5)))))

  (testing "filter edges - basic"
    (let [g {:nodes {:a identity
                     :b #(:a %)}
             :edges [[:a :b {:filter odd?}]]
             :start-node :a
             :end-node :b}]
      (is (= 1 (run-sync g 1)))
      (is (= nil (run-sync g 2)))))

  (testing "filter edges - advanced (including transform)"
    (let [g {:nodes {:a identity
                     :b #(:a %)
                     :c identity}
             :edges [[:a :b {:transform str
                             :filter #(= "4" %)}]
                     [:b :c]
                     [:a :c {:transform vector}]]
             :start-node :a
             :end-node :c}]
      (is (= {:a [4] :b "4"} (run-sync g 4)))
      (is (= {:a [5] :b nil} (run-sync g 5))))))
