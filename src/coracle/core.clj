(ns coracle.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [scenic.routes :as sr]
            [ring.util.response :as r]
            [ring.middleware.defaults :as ring-defaults]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [coracle.activity :as a]
            [coracle.config :as c]
            [coracle.db :as db]
            [coracle.jws :as jws]
            [coracle.migration :as m]))

(defn not-found-handler [_]
  (-> (r/response {:error "not found"}) (r/status 404)))

(defn ping [_]
  (-> (r/response "pong")
      (r/content-type "text/plain")))

(defn jwk-set [json-web-key-set _]
  (-> (r/response json-web-key-set)
      (r/content-type "application/json")))

(defn handlers [db external-jwk-set-url json-web-key-set jws-generator]
  {:add-activity               (partial a/add-activity db)
   :show-activities            (partial a/get-activities db external-jwk-set-url jws-generator)
   :ping                       ping
   :latest-published-timestamp (partial a/latest-published-timestamp db)
   :jwk-set                    (partial jwk-set json-web-key-set)})

(defn wrap-bearer-token [handler bearer-token]
  (fn [request]
    (let [request-method (:request-method request)
          request-bearer-token (get-in request [:headers "bearer-token"])]
      (cond
        (= :get request-method) (handler request)
        (= bearer-token request-bearer-token) (handler request)
        :default (do (log/warn (format "Unauthorised request with bearer-token [%s]" request-bearer-token))
                     (-> (r/response {:error "unauthorised"})
                         (r/content-type "application/json")
                         (r/status 401)))))))

(def routes (sr/load-routes-from-file "routes.txt"))

(defn handler [db config-m json-web-key-set jws-generator]
  (-> (sr/scenic-handler routes (handlers db (:external-jwk-set-url config-m) json-web-key-set jws-generator) not-found-handler)
      (wrap-bearer-token (:bearer-token config-m))
      (wrap-json-response)
      (ring-defaults/wrap-defaults (if (c/secure?)
                                     (assoc ring-defaults/secure-api-defaults :proxy true)
                                     ring-defaults/api-defaults))
      (wrap-json-body :keywords? false)))

(defn start-server [db config-m json-web-key-set jws-generator]
  (run-jetty (handler db config-m json-web-key-set jws-generator) {:port (:app-port config-m)
                                                                   :host (:app-host config-m)}))

(defn- generate-config-m []
  {:external-jwk-set-url (c/external-jwk-set-url)
   :app-host             (c/app-host)
   :app-port             (c/app-port)
   :bearer-token         (c/bearer-token)})

(defn -main [& _]
  (let [config-m (generate-config-m)
        db (db/connect-to-db (c/mongo-uri))
        json-web-key (jws/generate-json-web-key (jws/generate-key-id))
        json-web-key-set (jws/json-web-key->json-web-key-set json-web-key)
        jws-generator (jws/jws-generator json-web-key)]
    (m/run-migrations db)
    (start-server db config-m json-web-key-set jws-generator)))
