(ns core-test
  (:require [resilience4clj-cache.core :as c]
            [clojure.test :refer :all])

  (:import (javax.cache Caching)

           (javax.cache.configuration MutableConfiguration)))

;; mock for an external call
(defn ^:private external-call
  ([n]
   (external-call n nil))
  ([n {:keys [fail? wait]}]
   (when wait
     (Thread/sleep wait))
   (if-not fail?
     (str "Hello " n "!")
     (throw (ex-info "Couldn't say hello" {:extra-info :here})))))

(defn ^:private external-call!
  [a]
  (Thread/sleep 250)
  (swap! a inc))

(deftest create-default-cache
  (let [{:keys [eternal?]} (-> "my-cache"
                               c/create
                               c/config)]
    (is (= true eternal?))))

(deftest create-expire-after-cache
  (let [{:keys [eternal? expire-after]} (-> "my-cache"
                                            (c/create {:expire-after 1500})
                                            c/config)]
    (is (= false eternal?))
    (is (= 1500 expire-after))))

(deftest create-expire-out-of-range
  (try
    (-> "my-cache"
        (c/create {:expire-after 500})
        c/config)
    (catch Throwable t
      (is (= (-> t ex-data :resilience4clj.anomaly/category)
             :resilience4clj.anomaly/invalid-expire-after)))))

(deftest create-custom-factories
  (let [provider (fn [_] (Caching/getCachingProvider))
        manager (fn [provider _] (.getCacheManager provider))
        config (fn [_] (-> (MutableConfiguration.)
                           (.setTypes java.lang.String java.lang.Object)))
        {:keys [eternal?
                expire-after
                provider-fn
                manager-fn
                config-fn] :as bla} (-> "my-cache"
                                        (c/create {:provider-fn provider
                                                   :manager-fn manager
                                                   :config-fn config})
                                        c/config)]
    (is (nil? eternal?))
    (is (nil? expire-after))
    (is (= provider provider-fn))
    (is (= manager manager-fn))
    (is (= config config-fn))))

(deftest fallback-function
  (testing "non fallback option"
    (let [cache (c/create "my-cache")
          decorated (c/decorate external-call cache)]
      (is (thrown? Throwable (decorated "World!" {:fail? true})))
      (try
        (decorated "World!" {:fail? true})
        (catch Throwable e
          (is (= :here
                 (-> e ex-data :extra-info)))))))

  (testing "with fallback option"
    (let [fallback-fn (fn [{:keys [cause]} n opts]
                        (str "It should say Hello " n " but it didn't "
                             "because of a problem " (-> cause ex-data :extra-info name)))
          cache (c/create "my-cache")
          decorated (c/decorate external-call cache
                                {:fallback fallback-fn})]
      (is (= "It should say Hello World! but it didn't because of a problem here"
             (decorated "World!" {:fail? true}))))))

(deftest expiration-really-works
  (let [cache (c/create "my-cache" {:expire-after 5000})
        cached (c/decorate external-call cache)
        error-margin 0.10
        wait-duration 500]
    (let [start (. System (nanoTime))
          target-end (double wait-duration)]
      (dotimes [_ 30]
        (is (= "Hello Foobar!" (cached "Foobar" {:wait wait-duration}))))
      (let [end (/ (double (- (. System (nanoTime)) start)) 1000000.0)]
        (is (> end (* target-end (- 1 error-margin))))
        (is (< end (* target-end (+ 1 error-margin))))))
    (Thread/sleep 5500)
    (let [start (. System (nanoTime))
          target-end (double wait-duration)]
      (dotimes [_ 30]
        (is (= "Hello Foobar!" (cached "Foobar" {:wait wait-duration}))))
      (let [end (/ (double (- (. System (nanoTime)) start)) 1000000.0)]
        (is (> end (* target-end (- 1 error-margin))))
        (is (< end (* target-end (+ 1 error-margin))))))))

(deftest eternal-works
  (let [cache (c/create "my-cache" {:eternal? true})
        cached (c/decorate external-call cache)
        error-margin 0.20
        wait-duration 1000]
    (let [start (. System (nanoTime))
          target-end (double wait-duration)]
      (dotimes [_ 100]
        (is (= "Hello Foobar!" (cached "Foobar" {:wait wait-duration}))))
      (let [end (/ (double (- (. System (nanoTime)) start)) 1000000.0)]
        (is (> end (* target-end (- 1 error-margin))))
        (is (< end (* target-end (+ 1 error-margin))))))))

(deftest direct-manipulation
  (let [cache (c/create "my-cache")
        r1 (rand)
        r2 (rand)]

    (is (not (c/contains? cache :foo)))
    (is (not (c/contains? cache '(:foo))))
    (c/put! cache :foo r2)
    (is (= r2 (c/get cache :foo)))
    (is (= r2 (c/get cache '(:foo))))
    (is (c/contains? cache :foo))
    (is (c/contains? cache '(:foo)))

    (c/put! cache :foo r1)
    (is (= r1 (c/get cache :foo)))
    (is (= r1 (c/get cache '(:foo))))
    (is (c/contains? cache :foo))
    (is (c/contains? cache '(:foo)))

    (is (not (c/contains? cache [:foo :bar])))
    (c/put! cache [:foo :bar] (* r1 r2))
    (is (c/contains? cache [:foo :bar]))
    (is (= (* r1 r2) (c/get cache [:foo :bar])))))

(deftest cache-invalidation
  (let [cache (c/create "my-cache" {:eternal? true})
        cached (c/decorate external-call cache)
        error-margin 0.10
        wait-duration 200]
    (dotimes [n 2]
      (when (= 1 n)
        (c/invalidate! cache))
      (let [start (. System (nanoTime))
            target-end (double wait-duration)]
        (dotimes [_ 10]
          (is (= "Hello Foobar!" (cached "Foobar" {:wait wait-duration}))))
        (let [end (/ (double (- (. System (nanoTime)) start)) 1000000.0)]
          (is (> end (* target-end (- 1 error-margin))))
          (is (< end (* target-end (+ 1 error-margin)))))))))

(deftest metrics
  (let [cache (c/create "my-cache")
        cached (c/decorate external-call cache)]
    (is (= {:hits 0
            :misses 0
            :errors 0
            :manual-puts 0
            :manual-gets 0}
           (c/metrics cache)))
    (dotimes [_ 10]
      (cached "Foobar"))
    (is (= {:hits 9
            :misses 1
            :errors 0
            :manual-puts 0
            :manual-gets 0}
           (c/metrics cache)))
    (dotimes [_ 5]
      (try
        (cached "Foobar" {:fail? true})
        (catch Throwable _)))
    (is (= {:hits 9
            :misses 1
            :errors 5
            :manual-puts 0
            :manual-gets 0}
           (c/metrics cache)))
    (dotimes [n 3]
      (c/put! cache :a n))
    (dotimes [n 12]
      (c/get cache :a))
    (is (= {:hits 9
            :misses 1
            :errors 5
            :manual-puts 3
            :manual-gets 12}
           (c/metrics cache)))
    (c/reset! cache)
    (is (= {:hits 0
            :misses 0
            :errors 0
            :manual-puts 0
            :manual-gets 0}
           (c/metrics cache)))))

(deftest metrics-should-not-overflow
  (let [cache (c/create "my-cache")
        cached (c/decorate external-call cache)]
    ;; this is a tier violation of course
    ;; but it was the fastest way to test without having to simulate a loop
    ;; of Long/MAX_VALUE iterations
    (is (= 0
           (:manual-gets (c/metrics cache))))
    (swap! (:metrics cache) update :manual-gets (fn [_] Long/MAX_VALUE))
    (is (= Long/MAX_VALUE
           (:manual-gets (c/metrics cache))))
    (c/get cache :a)
    (is (= 0 (:manual-gets (c/metrics cache))))))
