(ns org.zalando.stups.friboo.system.db
  (:require [com.stuartsierra.component :refer [Lifecycle]]
            [org.zalando.stups.friboo.log :as log]
            [org.zalando.stups.friboo.config :refer [require-config]]
            [cheshire.generate]
            [com.netflix.hystrix.core :refer [defcommand]])
  (:import (com.jolbox.bonecp BoneCPDataSource)
           (org.flywaydb.core Flyway)
           (com.netflix.hystrix.exception HystrixBadRequestException)
           (com.fasterxml.jackson.databind.util ISO8601Utils)
           (java.util Properties)))

(defn load-flyway-configuration
  [configuration jdbc-url]
  (let [properties (Properties.)]
    (doseq [property configuration]
      (when (.contains (name (key property)) "flyway")
        (.setProperty properties (clojure.string/replace (name (key property)) "-" ".") (val property))))
    (.setProperty properties "flyway.driver" "")
    (.setProperty properties "flyway.url" jdbc-url)
    (.setProperty properties "flyway.user" (require-config configuration :user))
    (.setProperty properties "flyway.password" (require-config configuration :password))
    properties))

(defn start-component [{:as this :keys [configuration]}]
  (if (:datasource this)
    (do
      (log/debug "Skipping start of DB connection pool; already running.")
      this)

    (do
      (let [auto-migration? (:auto-migration? configuration true)
            jdbc-url        (str "jdbc:" (require-config configuration :subprotocol) ":" (require-config configuration :subname))]

        (when auto-migration?
          (log/info "Initiating automatic DB migration for %s." jdbc-url)
          (doto (Flyway.)
            (.configure (load-flyway-configuration configuration jdbc-url))
            (.migrate)))

        (log/info "Starting DB connection pool for %s." jdbc-url)
        (let [partitions (or (:partitions configuration) 3)
              min-pool   (or (:min-pool configuration) 6)
              max-pool   (or (:max-pool configuration) 21)
              datasource (doto (BoneCPDataSource.)
                           (.setJdbcUrl jdbc-url)
                           (.setUsername (require-config configuration :user))
                           (.setPassword (require-config configuration :password))
                           (.setMinConnectionsPerPartition (int (/ min-pool partitions)))
                           (.setMaxConnectionsPerPartition (int (/ max-pool partitions)))
                           (.setPartitionCount partitions)
                           (.setStatisticsEnabled true)
                           (.setIdleConnectionTestPeriodInMinutes 2)
                           (.setIdleMaxAgeInMinutes 10)
                           (.setInitSQL (or (:init-sql configuration) ""))
                           (.setConnectionTestStatement "SELECT 1"))]
          (assoc this :datasource datasource))))))

(defn stop-component [this]
  (if-not (:datasource this)
    (do
      (log/debug "Skipping stop of DB connection pool; not running.")
      this)

    (do
      (log/info "Stopping DB connection pool.")
      (.close (:datasource this))
      (assoc this :datasource nil))))

;; Defines a DB component
;; HINT: this component is itself a valid db-spec as its a map with the key 'datasource'
;; configuration can include {:auto-migration? false} to disable automatic migration
(defrecord DB [;; parameters (filled in by make-http on creation)
               configuration
               ;; dependencies (filled in by the component library before starting)
               ;; runtime vals (filled in by start-component)
               datasource
               ]
  Lifecycle
  (start [this]
    (start-component this))
  (stop [this]
    (stop-component this)))

;; #30 cheshire drops the milliseconds by default
(cheshire.generate/add-encoder java.sql.Timestamp
                               (fn [timestamp jsonGenerator]
                                 (.writeString jsonGenerator
                                               (str (ISO8601Utils/format timestamp true)))))

;; Helpers for hystrix wrapping

(defn ignore-nonfatal-exceptions
  "Default do-nothing nonfatal exception indicator. You would probably want to replace it with something that
  returns a non-nil string on bad requests errors"
  [e]
  nil)

(defmacro generate-hystrix-commands
  "Wraps all functions in the used namespace"
  [& {:keys [prefix suffix ignore-exception-fn? namespace]
      :or   {prefix               "cmd-"
             suffix               ""
             ignore-exception-fn? ignore-nonfatal-exceptions
             namespace            *ns*}}]
  `(do ~@(map (fn [[n f]]
                `(defcommand ~(symbol (str prefix (name n) suffix))
                   [& args#]
                   (try
                     (apply ~f args#)
                     (catch Throwable t#
                       (if-let [msg# (~ignore-exception-fn? t#)]
                         (throw (HystrixBadRequestException. msg# t#))
                         (throw t#))))))
              (ns-publics namespace))))
