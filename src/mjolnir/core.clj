(ns mjolnir.core
  "Mjölnir, a banhammer of the gods"
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.core.cache :as cache]))

(defn normalize [x]
  ;; convert to str for consistency
  ;; (and to avoid having to parse when persisting/reading)
  (string/replace (str x) "\n" ""))

(defn request->factors
  "Returns list of factor keys [[:factor-id factor-value] ...] "
  [opts request]
  (->> (opts :mjolnir.opts/factors)
       (map (fn [[factor-id factor-fn]]
              [factor-id (normalize (factor-fn request))]))))

(defn ban!
  [ban-store factor-key]
  (swap! ban-store conj factor-key))

(defn track-failure!
  "Updates fail-cache
    and updates ban-store if over limit of failures"
  [{:mjolnir.context/keys [fail-cache ban-store opts]} request]
  (doseq [factor-key (request->factors opts request)]
    (if-let [strike-count (cache/lookup @fail-cache factor-key)]
      (do (swap! fail-cache cache/miss factor-key (inc strike-count))
          (when (<= (opts :mjolnir.opts/max-strikes) (inc strike-count))
            (ban! ban-store factor-key)))
      (swap! fail-cache cache/miss factor-key 1))))

(defn banned?
  [{:mjolnir.context/keys [ban-store opts]} request]
  (->> (request->factors opts request)
       (some @ban-store)
       boolean))

(defn prep-for-saving
  "Given ban-store atom (of form #{ [:foo 1] [:foo 2] [:bar 3] })
   returns map in form: {:foo #{1 2} :bar #{3}}"
  [ban-store]
  (->> @ban-store
       (reduce (fn [memo [k v]]
                 (if (contains? memo k)
                   (update memo k conj v)
                   (assoc memo k #{v}))) {})))

(defn persist!
  "Persists ban-store to files"
  [{:mjolnir.context/keys [opts ban-store]}]
  (.mkdirs (io/file (opts :mjolnir.opts/storage-directory)))
  (doseq [[factor-id values] (prep-for-saving ban-store)]
    (spit (io/file (str (opts :mjolnir.opts/storage-directory) "/" (name factor-id)))
          (->> values
               sort
               (string/join "\n")))))

(defn slurp-bans
  [opts]
  (->> (file-seq (io/file (opts :mjolnir.opts/storage-directory)))
       (remove (fn [f] (.isDirectory f)))
       (mapcat (fn [f]
                 (let [factor-id (keyword (.getName f))]
                   (->> (string/split (slurp f) #"\n")
                        (map (fn [value]
                               [factor-id value]))))))
       set))

(defn load!
  "Fills up ban values from persisted files"
  [{:mjolnir.context/keys [ban-store opts]}]
  (reset! ban-store (slurp-bans opts)))

(defn make-context [opts]
  {:mjolnir.context/opts opts
   :mjolnir.context/fail-cache (atom (cache/ttl-cache-factory {} :ttl (opts :mjolnir.opts/ttl)))
   :mjolnir.context/ban-store (atom #{})})

(defn middleware
  [context handler]
  (fn [request]
    (if (banned? context request)
      {:status 400
       :body "Banned"}
      (let [response (handler request)]
        (if (not (<= 200 (response :status) 299))
          (do
            (track-failure! context request)
            response)
          response)))))
