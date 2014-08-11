(ns cmr.indexer.data.concepts.collection
  "Contains functions to parse and convert collection concept"
  (:require [clojure.string :as str]
            [clj-time.format :as f]
            [cheshire.core :as json]
            [camel-snake-kebab :as csk]
            [cmr.indexer.services.index-service :as idx]
            [cmr.indexer.data.elasticsearch :as es]
            [cmr.common.mime-types :as mt]
            [cmr.common.log :refer (debug info warn error)]
            [cmr.umm.related-url-helper :as ru]
            [cmr.indexer.data.concepts.temporal :as temporal]
            [cmr.indexer.data.concepts.attribute :as attrib]
            [cmr.indexer.data.concepts.science-keyword :as sk]
            [cmr.indexer.data.concepts.spatial :as spatial]
            [cmr.indexer.data.concepts.keyword :as k]
            [cmr.indexer.data.concepts.organization :as org])
  (:import cmr.spatial.mbr.Mbr))

(defn spatial->elastic
  [collection]
  (try
    (when-let [geometries (seq (get-in collection [:spatial-coverage :geometries]))]
      (let [sr (get-in collection [:spatial-coverage :spatial-representation])]
        ;; TODO Add support for all spatial representations and geometries
        (cond
          (or (= sr :geodetic) (= sr :cartesian))
          (spatial/spatial->elastic-docs sr collection)

          :else
          (info "Ignoring indexing spatial of collection spatial representation of" sr))))
    (catch Exception e
      (error e (format "Error generating spatial for collection: %s. Skipping spatial."
                       (pr-str collection))))))

(defmethod es/concept->elastic-doc :collection
  [context concept collection]
  (let [{:keys [concept-id provider-id revision-date format]} concept
        {{:keys [short-name long-name version-id processing-level-id collection-data-type]} :product
         :keys [entry-id entry-title summary temporal related-urls spatial-keywords associated-difs
                temporal-keywords]} collection
        platforms (:platforms collection)
        platform-short-names (map :short-name platforms)
        platform-long-names (remove nil? (map :long-name platforms))
        instruments (mapcat :instruments platforms)
        instrument-short-names (remove nil? (map :short-name instruments))
        instrument-long-names (remove nil? (map :long-name instruments))
        sensors (mapcat :sensors instruments)
        sensor-short-names (remove nil? (map :short-name sensors))
        sensor-long-names (remove nil? (map :long-name sensors))
        project-short-names (map :short-name (:projects collection))
        project-long-names (map :long-name (:projects collection))
        two-d-coord-names (map :name (:two-d-coordinate-systems collection))
        archive-center-val (org/extract-archive-centers collection)
        start-date (temporal/start-date :collection temporal)
        end-date (temporal/end-date :collection temporal)
        atom-links (map json/generate-string (ru/atom-links related-urls))
        ;; not empty is used below to get a real true/false value
        downloadable (not (empty? (ru/downloadable-urls related-urls)))
        browsable (not (empty? (ru/browse-urls related-urls)))
        update-time (get-in collection [:data-provider-timestamps :update-time])
        update-time (f/unparse (f/formatters :date-time) update-time)
        spatial-representation (get-in collection [:spatial-coverage :spatial-representation])]
    (merge {:concept-id concept-id
            :entry-id entry-id
            :entry-id.lowercase (str/lower-case entry-id)
            :entry-title entry-title
            :entry-title.lowercase (str/lower-case entry-title)
            :provider-id provider-id
            :provider-id.lowercase (str/lower-case provider-id)
            :short-name short-name
            :short-name.lowercase (str/lower-case short-name)
            :version-id version-id
            :version-id.lowercase (str/lower-case version-id)
            :revision-date revision-date
            :processing-level-id processing-level-id
            :processing-level-id.lowercase (when processing-level-id (str/lower-case processing-level-id))
            :collection-data-type collection-data-type
            :collection-data-type.lowercase (when collection-data-type (str/lower-case collection-data-type))
            :platform-sn platform-short-names
            :platform-sn.lowercase  (map str/lower-case platform-short-names)
            :instrument-sn instrument-short-names
            :instrument-sn.lowercase  (map str/lower-case instrument-short-names)
            :sensor-sn sensor-short-names
            :sensor-sn.lowercase  (map str/lower-case sensor-short-names)
            :project-sn project-short-names
            :project-sn.lowercase  (map str/lower-case project-short-names)
            :two-d-coord-name two-d-coord-names
            :two-d-coord-name.lowercase  (map str/lower-case two-d-coord-names)
            :spatial-keyword spatial-keywords
            :spatial-keyword.lowercase  (map str/lower-case spatial-keywords)
            :attributes (attrib/psas->elastic-docs collection)
            :science-keywords (sk/science-keywords->elastic-doc collection)
            :start-date (when start-date (f/unparse (f/formatters :date-time) start-date))
            :end-date (when end-date (f/unparse (f/formatters :date-time) end-date))
            :archive-center archive-center-val
            :archive-center.lowercase (map str/lower-case archive-center-val)
            :downloadable downloadable
            :browsable browsable
            :atom-links atom-links
            :summary summary
            :original-format (str/upper-case (name (mt/mime-type->format format)))
            :update-time update-time
            :associated-difs associated-difs
            :associated-difs.lowercase (map str/lower-case associated-difs)
            :coordinate-system (when spatial-representation (csk/->SNAKE_CASE_STRING spatial-representation))
            ;; fields added to support keyword searches
            :keyword (k/create-keywords-field collection)
            :long-name.lowercase (when long-name (str/lower-case long-name))
            :platform-ln.lowercase (map str/lower-case platform-long-names)
            :instrument-ln.lowercase (map str/lower-case instrument-long-names)
            :sensor-ln.lowercase (map str/lower-case sensor-long-names)
            :project-ln.lowercase (map str/lower-case project-long-names)
            :temporal-keyword.lowercase (map str/lower-case temporal-keywords)}
           (spatial->elastic collection))))

