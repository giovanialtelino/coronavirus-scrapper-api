(ns coronavirus-scrapper-api.postgresql
  (:require
    [clojure.java.jdbc :as jdbc]
    [java-time :as jt]))

(defn- pool-query
  ([conn query]
   (jdbc/with-db-connection [pool-conn conn]
                            (let [result (jdbc/query pool-conn query)]
                              result)))
  ([conn query args]
   (jdbc/with-db-connection [pool-conn conn]
                            (let [result (jdbc/query pool-conn [query args])]
                              result))))

(defn get-timeline-by-id [database id]
  (pool-query database ["SELECT recovered, confirmed, deaths, date FROM coronavirus WHERE index_id = ? ORDER BY date DESC"] id))

(defn get-last-update-date [database]
  (:max (nth (pool-query database ["SELECT MAX (date) FROM coronavirus"]) 0)))

(defn get-latest [database]
  (pool-query database ["SELECT country, SUM(deaths) AS deaths, SUM(recovered) AS recovered, SUM(confirmed) AS confirmed FROM coronavirus
  WHERE aggregated = 'CT' GROUP BY date, country ORDER BY date DESC LIMIT 1"]))

(defn get-locations [database country_code timelines]
  (let [all-query "SELECT index_id, country, province, location, recovered, confirmed, deaths, url, population, aggregated, date
                    FROM coronavirus ORDER BY date DESC LIMIT 1"
        country-code-query "SELECT CO.index_id, CT.iso3, CT.name, CO.province, CO.location, CO.recovered, CO.confirmed, CO.deaths, CO.url, CO.population, CO.aggregated, CO.date
                            FROM coronavirus CO
                            INNER JOIN country CT ON CO.country = CT.id
                            WHERE CT.iso3 = ?
                            ORDER BY date DESC LIMIT 1"
        query (if (nil? country_code)
                (pool-query database all-query)
                (pool-query database country-code-query country_code)
                )]
    (if (nil? timelines)
      query
      (map get-timeline-by-id (:index_id query)))))

(defn get-location-by-id [database id]
  (pool-query database ["SELECT index_id, country, province, location, recovered, confirmed, deaths, url, population, aggregated, date, timeline FROM coronavirus
   WHERE timeline IN (SELECT recovered, confirmed, deaths, date FROM coronavirus WHERE index_id = ? ORDER BY date DESC) AND index_id = ?
   ORDER BY date DESC LIMIT 1"] [id id]))

(defn- get-country-id [database country-abrev]
  (pool-query database "SELECT id FROM country WHERE iso3 = ?" country-abrev))

(defn post-data [database date json]
  (loop [i (dec (count json))
         added []]
    (if (>= i 0)
      (do
        (let [current-i (nth json i)
              state-county (if (nil? (:county current-i))
                             (:state current-i)
                             (:county current-i))
              country-id (:id (first (get-country-id database (:country current-i))))
              return (jdbc/insert! database :coronavirus {:index_id   (:featureId current-i)
                                                          :country    country-id
                                                          :province   state-county

                                                          :recovered  (:recovered current-i)
                                                          :confirmed  (:cases current-i)
                                                          :deaths     (:deaths current-i)
                                                          :url        (:url current-i)
                                                          :population (:population current-i)
                                                          :aggregated (:aggregate current-i)
                                                          :tested     (:tested current-i)
                                                          :rating     (:rating current-i)
                                                          :active     (:active current-i)
                                                          :date       (jt/to-sql-date date)})]
          (recur (dec i) (into added return))))
      added)))

(defn delete-data [database date]
  (pool-query database ["DELETE FROM coronavirus WHERE date = ?"] date))