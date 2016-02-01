(ns gclouj.bigquery
  (:import [com.google.gcloud.bigquery BigQueryOptions BigQuery$DatasetListOption DatasetInfo DatasetId BigQuery$TableListOption TableInfo TableId BigQuery$DatasetOption BigQuery$TableOption Schema Field Field$Type Field$Mode TableInfo$StreamingBuffer InsertAllRequest InsertAllRequest$RowToInsert InsertAllResponse BigQueryError BigQuery$DatasetDeleteOption QueryRequest QueryResponse QueryResult JobId Field Field$Type$Value FieldValue FieldValue$Attribute]
           [com.google.common.hash Hashing]))


(defmulti field-value->clojure (fn [attribute val]
                                 attribute))
(defmethod field-value->clojure FieldValue$Attribute/PRIMITIVE [_ ^FieldValue val]
  (.value val))

(defprotocol ToClojure
  (to-clojure [_]))
(extend-protocol ToClojure
  DatasetId
  (to-clojure [x] {:dataset-id (.dataset x)
                   :project-id (.project x)})
  DatasetInfo
  (to-clojure [x] {:creation-time (.creationTime x)
                   :dataset-id    (to-clojure (.datasetId x))
                   :description   (.description x)
                   :friendly-name (.friendlyName x)
                   :location      (.location x)
                   :last-modified (.lastModified x)})
  TableId
  (to-clojure [x] {:dataset-id (.dataset x)
                   :project-id (.project x)
                   :table-id   (.table x)})
  TableInfo$StreamingBuffer
  (to-clojure [x] {:estimated-bytes   (.estimatedBytes x)
                   :estimated-rows    (.estimatedRows x)
                   :oldest-entry-time (.oldestEntryTime x)})
  TableInfo
  (to-clojure [x] {:location           (.location x)
                   :friendly-name      (.friendlyName x)
                   :description        (.description x)
                   :bytes              (.numBytes x)
                   :rows               (.numRows x)
                   :creation-time      (.creationTime x)
                   :expiration-time    (.expirationTime x)
                   :last-modified-time (.lastModifiedTime x)
                   :streaming-buffer   (when-let [sb (.streamingBuffer x)]
                                         (to-clojure sb))
                   :table-id           (to-clojure (.tableId x))})
  BigQueryError
  (to-clojure [x] {:reason   (.reason x)
                   :location (.location x)
                   :message  (.message x)})
  InsertAllResponse
  (to-clojure [x] {:errors (->> (.insertErrors x)
                                (map (fn [[idx errors]]
                                       {:index  idx
                                        :errors (map to-clojure errors)}))
                                (seq))})
  JobId
  (to-clojure [x] {:project-id (.project x)
                   :job-id     (.job x)})
  Field
  (to-clojure [x] {:name (.name x)
                   :mode ({Field$Mode/NULLABLE :nullable
                           Field$Mode/REPEATED :repeated
                           Field$Mode/REQUIRED :required} (.mode x))
                   :type ({Field$Type$Value/BOOLEAN   :bool
                           Field$Type$Value/FLOAT     :float
                           Field$Type$Value/INTEGER   :integer
                           Field$Type$Value/RECORD    :record
                           Field$Type$Value/STRING    :string
                           Field$Type$Value/TIMESTAMP :timestamp} (.. x type value))})
  FieldValue
  (to-clojure [x] (field-value->clojure (.attribute x) x))
  Schema
  (to-clojure [x] (map to-clojure (.fields x)))
  QueryResponse
  (to-clojure [x] {:completed? (.jobCompleted x)
                   :errors     (->> (.executionErrors x) (map to-clojure) (seq))
                   :job-id     (to-clojure (.jobId x))
                   :results    (map (fn [fields] (map to-clojure fields))
                                    (iterator-seq (.. x result iterateAll)))
                   :schema     (to-clojure (.. x result schema))
                   :cache-hit  (.. x result cacheHit)}))

(defn service
  ([] (.service (BigQueryOptions/defaultInstance))))

(defn datasets [service]
  (let [it (-> service
               (.listDatasets (into-array BigQuery$DatasetListOption []))
               (.iterateAll))]
    (map to-clojure (iterator-seq it))))

(defn tables [service {:keys [project-id dataset-id] :as dataset}]
  (let [it (-> service
               (.listTables (DatasetId/of project-id dataset-id)
                            (into-array BigQuery$TableListOption []))
               (.iterateAll))]
    (map to-clojure (iterator-seq it))))

(defn table [service {:keys [project-id dataset-id table-id] :as table}]
  (to-clojure (.getTable service
                         (TableId/of project-id dataset-id table-id)
                         (into-array BigQuery$TableOption []))))

(defn create-dataset [service {:keys [project-id dataset-id friendly-name location description table-lifetime-millis] :as dataset}]
  (let [locations {:eu "EU"
                   :us "US"}
        builder   (DatasetInfo/builder project-id dataset-id)]
    (when friendly-name
      (.friendlyName builder friendly-name))
    (when description
      (.description builder description))
    (when table-lifetime-millis
      (.defaultTableLifetime builder table-lifetime-millis))
    (.location builder (or (locations location) "US"))
    (to-clojure (.create service (.build builder) (into-array BigQuery$DatasetOption [])))))

(defn delete-dataset [service {:keys [project-id dataset-id delete-contents?] :as dataset}]
  (let [options (if delete-contents?
                  [(BigQuery$DatasetDeleteOption/deleteContents)]
                  [])]
    (.delete service
             (DatasetId/of project-id dataset-id)
             (into-array BigQuery$DatasetDeleteOption options))))

(defn- mkfield [{:keys [name type description mode fields]}]
  (let [field-type (condp = type
                     :bool      (Field$Type/bool)
                     :float     (Field$Type/floatingPoint)
                     :integer   (Field$Type/integer)
                     :string    (Field$Type/string)
                     :timestamp (Field$Type/timestamp)
                     :record    (Field$Type/record (map mkfield fields)))
        builder    (Field/builder name field-type)
        field-mode ({:nullable  (Field$Mode/NULLABLE)
                     :repeated  (Field$Mode/REPEATED)
                     :required  (Field$Mode/REQUIRED)} (or mode :nullable))]
    (.mode builder field-mode)
    (.build builder)))

(defn- mkschema
  [fields]
  (let [builder (Schema/builder)]
    (.fields builder (into-array Field (->> fields (map mkfield))))
    (.build builder)))

(defn create-table
  "Fields: sequence of fields representing the table schema.
  e.g. [{:name \"foo\" :type :record :fields [{:name \"bar\" :type :integer}]}]"
  [service {:keys [project-id dataset-id table-id] :as table} fields]
  (let [builder (TableInfo/builder (TableId/of project-id dataset-id table-id)
                                   (mkschema fields))
        table-info (.build builder)]
    (to-clojure (.create service table-info (into-array BigQuery$TableOption [])))))

(defn delete-table
  [service {:keys [project-id dataset-id table-id] :as table}]
  (.delete service (TableId/of project-id dataset-id table-id)))

(defn row-hash
  "Creates a hash suitable for identifying duplicate rows, useful when
  streaming to avoid inserting duplicate rows."
  [m & {:keys [bits] :or {bits 128}}]
  (-> (Hashing/goodFastHash bits) (.hashUnencodedChars (pr-str m)) (.toString)))

(defn insert-all
  "Performs a streaming insert of rows. row-id can be a function to
  return the unique identity of the row (e.g. row-hash). Template suffix
  can be used to create tables according to a template."
  [service {:keys [project-id dataset-id table-id skip-invalid? template-suffix row-id] :as table} rows]
  (let [builder (InsertAllRequest/builder (TableId/of project-id dataset-id table-id)
                                          (map (fn [row]
                                                 (if row-id
                                                   (InsertAllRequest$RowToInsert/of (row-id row) row)
                                                   (InsertAllRequest$RowToInsert/of row)))
                                               rows))]
    (when template-suffix
      (.templateSuffix builder template-suffix))
    (->> builder
         (.build)
         (.insertAll service)
         (to-clojure))))



(defn query [service query {:keys [project-id dataset-id] :as dataset} {:keys [max-results dry-run? max-wait-millis use-cache?] :as query-opts}]
  (let [builder (QueryRequest/builder query)]
    (.defaultDataset builder (DatasetId/of project-id dataset-id))
    (when max-results
      (.maxResults builder max-results))
    (when dry-run?
      (.dryRun builder dry-run?))
    (when max-wait-millis
      (.maxWaitTime builder max-wait-millis))
    (when use-cache?
      (.useQueryCache builder use-cache?))
    (let [q (.build builder)]
      (to-clojure (.query service q)))))