(ns bixi.core
  (:require [schema.core :as s]
            [yada.yada :as yada]
            [hiccup.page :refer [html5]]
            [aleph.http :as http]
            [byte-streams :as bs]
            [hiccup.table :refer [to-table1d]]
            [cheshire.core :refer [parse-string]]))

;; Server atom holds instance in order to shutdown with url request
(def server (atom nil))

;; Basic Authentication --------------------------------------------------------------
(defn- restricted-content [ctx]
  (html5
    [:body
     [:h1 (format "Hello %s!" (get-in ctx [:authentication "default" :user]))]
     [:p "You're accessing a restricted resource!"]
     [:pre (pr-str (get-in ctx [:authentication "default"]))]]))

(defn basic-auth
  []
  (yada/resource
    {:id :bixi.resources/basic-auth
     :methods
         {:get {:produces "text/html"
                :response (fn [ctx] (restricted-content ctx))}}

     :access-control
         {:scheme        "Basic"
          :verify        (fn [[user password]]
                           (when (and (= user "jon") (= password "pither"))
                             {:user  user
                              :roles #{:user}}))
          :authorization {:methods {:get :user}}}}))
;; Basic Authentication --------------------------------------------------------------

;; BIXI Bike Table -------------------------------------------------------------------
;; TODO: config should be external and not hard coded, assumed ok for demo project
;; TODO: Code assumes locations are closest to the Leyton tube station. There are API methods
;; that take geo requests, however seems overkill for this implementation.
(def bixi-ids ["BikePoints_784"
                "BikePoints_785"
                "BikePoints_786"
                "BikePoints_787"
                "BikePoints_788"])

;; TODO: function is somewhat brittle where input does not conform.
(defn extract-bixi-data
  [d]
  (let [n (d "commonName")
        id (d "id")
        ap (d "additionalProperties")
        nBikes (filter #(= "NbBikes" (% "key")) ap)
        v ((first nBikes) "value")]
    (hash-map :id id
      :name n
      :bikes v))
  )

;; TODO: URL, api token and key are hard coded. Should move out to config file.
;; TODO: Code does not make use of aynchronous nature of Aleph, could be improved.
(defn get-bixi-bike-point
  [id]
  (-> @(http/get (format "https://api.tfl.gov.uk/BikePoint/%s?app_id=71d41567&app_key=d0c57f648c95a9c5f29fb8a61d961735" id))
    :body
    bs/to-string
    parse-string
    extract-bixi-data))


(defn bixi-bikes-html [ctx]
  (html5
    [:body
     [:h1 "Bixi Bike locations near Leyton"]
     [:p "We are trying to get some data..."]
     [:div (hiccup.table/to-table1d (map get-bixi-bike-point bixi-ids)
                                    [:name "Name" :id "id" :bikes "#bikes"])
      ]]))

(defn bixi-bikes []
  (yada/resource
    {:id :bixi.resources/bixi-bikes
     :methods
         {:get {:produces "text/html"
                :response bixi-bikes-html}}}))

(defn routes
  []
  ["/"
   {""             (yada/as-resource "Browse to /auth for Basic Auth demo and /bixi for bike info...")
    "auth"              (basic-auth)
    "bixi"              (bixi-bikes)
    "die"               (yada/as-resource (fn []
                                            (future (Thread/sleep 100) (@server))
                                            "shutting down in 100ms..."))}])
(defn run
  "Returns promise to capture lifecycle of server"
  [port-number]
  (let [listener     (yada/listener (routes) {:port (Integer. port-number)})
        done         (promise)]
    (reset! server (fn []
                     ((:close listener))
                     (deliver done :done)))
    done))

(defn -main
  [port-number & args]
  (let [done (run port-number)]
    (println "server running on port 3000... GET \"http://localhost:" port-number "/die\" to kill")
    @done))
