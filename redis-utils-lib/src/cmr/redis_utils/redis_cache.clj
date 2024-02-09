(ns cmr.redis-utils.redis-cache
  "An implementation of the CMR cache protocol on top of Redis. Mutliple applications
  have multiple caches which use one instance of Redis. Therefore calling reset on
  a Redis cache should not delete all of the data stored. Instead when creating a Redis
  cache the caller must provide a list of keys which should be deleted when calling
  reset on that particular cache. TTL MUST be set if you expect your keys to be evicted automatically."
  (:require
   [clojure.edn :as edn]
   [cmr.common.cache :as cache]
   [cmr.common.log :as log :refer [debug info warn error]]
   [cmr.redis-utils.redis :as redis :refer [wcar*]]
   [taoensso.carmine :as carmine]))

(defn serialize
  "Serializes v."
  [v]
  (pr-str v))

(defn deserialize
  "Deserializes v."
  [v]
  (when v (edn/read-string v)))

;; Implements the CmrCache protocol by saving data in Redis.
(defrecord RedisCache
  [
   ;; A collection of keys used by this cache. Only these keys will be deleted from the backend
   ;; store on calls to reset.
   keys-to-track

   ;; The time to live for the key in seconds.
   ttl

   ;; Refresh the time to live when GET operations are called on key.
   refresh-ttl?]

  cache/CmrCache
  (get-keys
    [this]
    (map deserialize (redis/get-keys)))

  (key-exists
    [this key]
    ;; key is the cache-key. Returns true if the cache key exists in redis, otherwise returns nil.
    (let [exists (wcar* (carmine/exists (serialize key)))]
      (when exists
        (> exists 0))))

  (get-value
    [this key]
    (info (format "Redis cache get timed function %s starting" key))
    (let [s-key (serialize key)
          result (-> (wcar* :as-pipeline
                            (carmine/get s-key)
                            (when refresh-ttl? (carmine/expire s-key ttl)))
                     first
                     :value)]
      (info (format "Redis cache get timed function %s finished" key))
      result))

  (get-value
    [this key lookup-fn]
    (info (format "Redis cache get lookup timed function %s starting" key))
    (let [cache-value (cache/get-value this key)
          result (if-not (nil? cache-value)
                   cache-value
                   (let [value (lookup-fn)]
                     (cache/set-value this key value)
                     value))]
      (info (format "Redis cache get lookup timed function %s finished" key))
      result))

  (reset
    [this]
    (doseq [the-key keys-to-track]
      (wcar* (carmine/del (serialize the-key)))))

  (set-value
    [this key value]
    ;; Store value in map to aid deserialization of numbers.
    (info (format "Redis cache save timed function %s starting" key))
    (let [s-key (serialize key)
          result (wcar* (carmine/set s-key {:value value})
                        (when ttl (carmine/expire s-key ttl)))]
      (info (format "Redis cache save timed function %s finished" key))
      result))

  (cache-size
    [_]
    (reduce #(+ %1 (if-let [size (wcar* (carmine/memory-usage (serialize %2)))]
                     size
                     0))
            0
            keys-to-track)))

(defn create-redis-cache
  "Creates an instance of the redis cache.
  options:
      :keys-to-track
       The keys that are to be managed by this cache.
      :ttl
       The time to live for the key in seconds. If nil assumes key will never expire. NOTE:
       The key is not guaranteed to stay in the cache for up to ttl. If the
       cache becomes full any key that is not set to persist will
       be a candidate for eviction.
      :refresh-ttl?
       When a GET operation is called called on the key then the ttl is refreshed
       to the time to live set by the initial cache."
  ([]
   (create-redis-cache nil))
  ([options]
   (->RedisCache (:keys-to-track options)
                 (get options :ttl)
                 (get options :refresh-ttl? false))))
