(ns cmr.umm.dif.collection.location-keywords
  "Provide functions to parse and generate DIF location / spatial keyword elements."
  (:require
   [clojure.set :as set]
   [cmr.common.xml :as cx]
   [cmr.common-app.services.kms-lookup :as kms-lookup]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The following functions are all copied directly from the cmr.umm-spec.location-keywords namespace
(def location-keyword-order
  "Defines the order of hierarchical keywords for LocationKeywords"
  [:Category :Type :Subregion1 :Subregion2 :Subregion3])

(def cache-location-keywords->umm-location-keywords
  "Mapping for renaming values generated by the kms fetcher to LocationKeyword UMM keys"
  {:category :Category
   :type :Type
   :subregion-1 :Subregion1
   :subregion-2 :Subregion2
   :subregion-3 :Subregion3
   :detailed-location :DetailedLocation})

(defn- find-spatial-keyword
  "Finds spatial keywords in the hierarchy and pick the one with the fewest keys (e.g. shortest
  hierarchical depth.) Takes the request context and a location string as parameters, and returns
  the map of hierarichies which contain the location string (treated case insensitive)."
  [context location-string]
  (or (kms-lookup/lookup-by-location-string context location-string)
      {:category "OTHER" :type location-string}))

(defn spatial-keywords->location-keywords
  "Takes the request context and a list of Spatial Keywords and returns a list of location keyword maps
  for that spatial keyword."
  [context spatial-keywords]
  (map (fn [keyword]
         (dissoc
          (set/rename-keys
            (find-spatial-keyword context keyword)
            cache-location-keywords->umm-location-keywords)
          :uuid))
       spatial-keywords))

(defn- location-values
  "Returns the location keyword values in order so that we can get the last one"
  [location-keyword]
  (for [k location-keyword-order
        :let [value (get location-keyword k)]
        :when value]
    value))

(defn- leaf-value
  "Returns the leaf value of the location-keyword object to be put in a SpatialKeywords list"
  [location-keyword]
  (last (location-values location-keyword)))

(defn location-keywords->spatial-keywords
  "Converts a list of LocationKeyword maps to a list of SpatialKeywords"
  [location-keyword-list]
  (map #(leaf-value %) location-keyword-list))

(defn translate-spatial-keywords
  "Translates a list of spatial keywords into an array of LocationKeyword type objects"
  [context spatial-keywords]
  (let [location-keyword-maps (spatial-keywords->location-keywords context spatial-keywords)
        umm-location-keyword-maps (seq
                                   (map
                                    #(dissoc
                                      (set/rename-keys % cache-location-keywords->umm-location-keywords)
                                      :uuid)
                                    location-keyword-maps))]
    umm-location-keyword-maps))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn xml-elem->location-keyword
  "Return a location keyword from a location element."
  [location-elem]
  (let [category (cx/string-at-path location-elem [:Location_Category])
        type (cx/string-at-path location-elem [:Location_Type])
        subregion-1 (cx/string-at-path location-elem [:Location_Subregion1])
        subregion-2 (cx/string-at-path location-elem [:Location_Subregion2])
        subregion-3 (cx/string-at-path location-elem [:Location_Subregion3])
        detailed-location (cx/string-at-path location-elem [:Detailed_Location])]
    {:Category category
     :Type type
     :Subregion1 subregion-1
     :Subregion2 subregion-2
     :Subregion3 subregion-3
     :DetailedLocation detailed-location}))

(defn xml-elem->spatial-keywords
  "Returns spatial keywords from all of the Location elements within a DIF9 or DIF10 collection."
  [collection-element]
  (seq
   (location-keywords->spatial-keywords
     (map xml-elem->location-keyword
          (cx/elements-at-path collection-element [:Location])))))
