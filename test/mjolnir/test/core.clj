(ns mjolnir.test.core
  (:require
   [clojure.test :refer [deftest testing is]]
   [mjolnir.core :as mjolnir]))

(deftest middleware
  (let [opts {;; how many failure attempts allowed (within time period) before banned
              :mjolnir.opts/storage-directory "mjolnir"
              :mjolnir.opts/max-strikes 3
              ;; how long (in ms) will store failures in cache
              :mjolnir.opts/ttl (* 1000 60 60)
              :mjolnir.opts/factors
              {:ip :remote-addr
               :foo (fn [request]
                      (get-in request [:body-params :foo]))}}
        mjolnir-context (mjolnir/make-context opts)
        pass-status 200
        fail-status 500
        ban-status 400
        raw-handler (fn [request]
                      (if (request :fail?)
                        {:status fail-status}
                        {:status pass-status}))
        ok-request {:remote-addr "127.0.0.1"
                    :body-params {:foo "John"}}
        fail-request {:fail? true
                      :remote-addr "0.0.0.0"
                      :body-params {:foo "j"}}
        fail-request-2 {:remote-addr "0.0.0.0"
                        :body-params {:foo "John"}}
        prepped-middleware (partial mjolnir/middleware mjolnir-context)
        wrapped-handler (prepped-middleware raw-handler)]

    (is (mjolnir/request->factors opts ok-request))

    (testing "normal request works"
      (is (= pass-status (:status (wrapped-handler ok-request)))))

    (testing "make three identical fail requests"
      (is (= fail-status (:status (wrapped-handler fail-request))))
      (is (= fail-status (:status (wrapped-handler fail-request))))
      (is (= fail-status (:status (wrapped-handler fail-request)))))

    (testing "normal request still works"
      (is (= pass-status (:status (wrapped-handler ok-request)))))

    (testing "fail request now banned"
      (is (= ban-status (:status (wrapped-handler fail-request)))))

    (testing "modified fail request also banned"
      (is (= ban-status (:status (wrapped-handler fail-request-2)))))))

(deftest ban-store-round-trip
  (let [mjolnir-context (mjolnir/make-context
                         {:mjolnir.opts/storage-directory "mjolnir"
                          :mjolnir.opts/ttl 50000})
        bans #{[:foo "1"] [:foo "2"] [:bar "3"]}
        _ (reset! (mjolnir-context :mjolnir.context/ban-store) bans)]
    (mjolnir/persist! mjolnir-context)
    (is (= bans (mjolnir/slurp-bans (mjolnir-context :mjolnir.context/opts))))
    (mjolnir/load! mjolnir-context)
    (is (= bans @(mjolnir-context :mjolnir.context/ban-store)))))
