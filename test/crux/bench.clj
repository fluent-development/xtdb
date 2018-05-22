(ns crux.bench
  (:require [crux.fixtures :as f :refer [random-person *kv*]]
            [crux.byte-utils :as bu]
            [crux.kv :as cr]
            [crux.query :as q]
            [crux.core :refer [db]]
            [crux.doc :as doc]
            [crux.codecs]
            [crux.memdb])
  (:import [java.util Date]))

(defn bench [& {:keys [n batch-size ts queries kv] :or {n 1000
                                                        batch-size 10
                                                        queries 100
                                                        ts (Date.)
                                                        kv :rocks}}]
  ((case kv
     :rocks f/with-rocksdb
     :lmdb f/with-lmdb
     :mem f/with-memdb)
   (fn []
     (f/with-kv-store
      (fn []
        ;; Insert data
        (time
         (doseq [[i people] (map-indexed vector (partition-all batch-size (take n (repeatedly random-person))))]
           (cr/-put *kv* people ts)))

        ;; Basic query
        (time
         (doseq [i (range queries)]
           (q/q (db *kv*) {:find ['e]
                           :where [['e :name "Ivan"]]}))))))))

;; Datomic: 100 queries against 1000 dataset = 40-50 millis

;; ~500 mills for 1 million
(defn bench-encode [n]
  (let [d (java.util.Date.)]
    (doseq [_ (range n)]
      (cr/encode cr/frame-index-eat {:index :eat :eid (rand-int 1000000) :aid (rand-int 1000000) :ts d}))))

;; ~900 ms for 1 million
;; TODO: add new test here, the value frames have been replaced by nippy.
#_(defn bench-decode [n]
  (let [f (cr/encode cr/frame-value-eat {:type :string :v "asdasd"})]
    (doseq [_ (range n)]
      (crux.codecs/decode cr/frame-value-eat f))))

;; Notes codecs benching:
;; in the current world - m is problematic, as it's a map
;; decode is also likely more expensive, due to enum dispatch and the for loop

(defn bench-doc [& {:keys [n batch-size ts queries kv] :or {n 1000
                                                            batch-size 10
                                                            queries 100
                                                            ts (Date.)
                                                        kv :rocks}}]
  ((case kv
     :rocks f/with-rocksdb
     :lmdb f/with-lmdb
     :mem f/with-memdb)
   (fn []
     (f/with-kv-store
      (fn []
        ;; Insert data
        (time
         (doseq [[i people] (map-indexed vector (partition-all batch-size (take n (repeatedly random-person))))
                 :let [transact-time (Date.)]]
           (doc/store-docs *kv* people)
           (doc/store-txs *kv*
                          (vec (for [person people]
                                 [:crux.tx/put
                                  (:crux.kv/id person)
                                  (bu/bytes->hex (doc/doc->content-hash person))]))
                          transact-time
                          0)))

        ;; Basic query, does not do temporal look up yet.
        (time
         (doseq [i (range queries)
                 :let [transact-time (Date.)]]
           (q/q (doc/map->DocDatasource {:kv *kv*
                                         :transact-time transact-time
                                         :business-time transact-time})
                '{:find [e]
                  :where [[e :name "Ivan"]]}))))))))
