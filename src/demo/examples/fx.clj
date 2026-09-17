(ns demo.examples.fx
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [demo.registry :as reg]
            [jolt.http-client :as http]))

(def currencies ["USD" "EUR" "GBP" "JPY" "CHF" "CAD" "AUD"])

(defonce state (atom {:from 0 :to 1 :amount "1"
                      :rate nil :date nil :status :idle}))

(defn- parse-number [s]
  (let [s (str/trim (or s ""))]
    (when (re-matches #"-?[0-9]+(\.[0-9]+)?" s)
      (edn/read-string s))))

(defn- fmt-2dp [n]
  (let [cents (long (+ 0.5 (* 100.0 n)))]
    (str (quot cents 100) "." (let [c (rem cents 100)]
                                (if (< c 10) (str "0" c) c)))))

;; --- the fetch ---------------------------------------------------------------

(defn- parse-rates
  "Frankfurter's response, parsed properly: {:rate <number> :date <string>}.
  data.json is pure Clojure; under jolt it needs io.github.jolt-lang/time for
  java.time (RFC 0008) — the load error names that dep itself."
  [body to]
  (let [m (json/read-str (or body "{}") :key-fn keyword)]
    {:rate (get-in m [:rates (keyword to)])
     :date (:date m)}))

(defn fetch-rate!
  "Fetch the current pair's rate on a FIBER (async/io-thread — the socket
  read parks it instead of holding an OS thread); results re-enter as
  actions, tagged with the pair they answer so a stale response can't win."
  []
  (let [{:keys [from to]} @state
        from-c (currencies from)
        to-c   (currencies to)
        pair   [from-c to-c]]
    (if (= from-c to-c)
      (swap! state assoc :rate 1.0 :date nil :status :ok)
      (do
        (swap! state assoc :status :loading :rate nil)
        (async/io-thread
          (try
            (let [resp (http/get (str "https://api.frankfurter.app/latest?from="
                                      from-c "&to=" to-c)
                                 {:conn-timeout 5000 :socket-timeout 10000})
                  {:keys [rate date]} (parse-rates (:body resp) to-c)]
              (reg/execute-actions
               nil [[:action.fx/rate-arrived pair rate date]]))
            (catch :default _
              (reg/execute-actions nil [[:action.fx/rate-failed pair]]))))))))

;; --- view --------------------------------------------------------------------

(defn view [{:keys [from to amount rate date status]}]
  (let [amt    (parse-number amount)
        from-c (currencies from)
        to-c   (currencies to)]
    [:vbox {:spacing 12 :margin 16}
     [:label {:markup [:span {:size "large" :weight "bold"} "Live Currency"]}]
     [:hbox {:spacing 8}
      [:label {:label "From:"}]
      [:drop-down {:items currencies :selected from
                   :on {:selected-changed [[:action.fx/set-from]]}}]
      [:label {:label "To:"}]
      [:drop-down {:items currencies :selected to
                   :on {:selected-changed [[:action.fx/set-to]]}}]]
     [:hbox {:spacing 8}
      [:label {:label "Amount:"}]
      [:entry {:text amount :width-request 120
               :on {:change [[:action.fx/set-amount]]}}]]
     [:label {:markup
              (case status
                :loading [:span {:foreground "#8e939d"} "Fetching rate…"]
                :error   [:span {:foreground "#c04040"}
                          "Rate fetch failed — check the connection."]
                (if (and rate amt)
                  [:span {:size "large"}
                   (str amount " " from-c " = " (fmt-2dp (* amt rate)) " " to-c)]
                  [:span {:foreground "#8e939d"} "—"]))}]
     [:label {:markup [:span {:size "small" :foreground "#8e939d"}
                       (if (and rate date)
                         (str "1 " from-c " = " rate " " to-c " · ECB " date)
                         " ")]}]]))

;; --- actions -----------------------------------------------------------------

(s/def ::pair (s/coll-of string? :count 2))

(defmethod reg/action-spec :action.fx/set-from [_]
  (s/cat :kind #{:action.fx/set-from}))
(defmethod reg/action-spec :action.fx/set-to [_]
  (s/cat :kind #{:action.fx/set-to}))
(defmethod reg/action-spec :action.fx/set-amount [_]
  (s/cat :kind #{:action.fx/set-amount}))
(defmethod reg/action-spec :action.fx/rate-arrived [_]
  (s/cat :kind #{:action.fx/rate-arrived} :pair ::pair
         :rate (s/nilable number?) :date (s/nilable string?)))
(defmethod reg/action-spec :action.fx/rate-failed [_]
  (s/cat :kind #{:action.fx/rate-failed} :pair ::pair))

(defmethod reg/run-action! :action.fx/set-from [event _]
  (when-let [i (reg/event-value event)]
    (swap! state assoc :from i)
    (fetch-rate!)))

(defmethod reg/run-action! :action.fx/set-to [event _]
  (when-let [i (reg/event-value event)]
    (swap! state assoc :to i)
    (fetch-rate!)))

(defmethod reg/run-action! :action.fx/set-amount [event _]
  (swap! state assoc :amount (reg/event-value event)))

(defmethod reg/run-action! :action.fx/rate-arrived [_ [_ pair rate date]]
  (let [{:keys [from to]} @state]
    ;; The race guard: apply only if this answers the CURRENT selection.
    (when (= pair [(currencies from) (currencies to)])
      (swap! state assoc
             :rate rate :date date
             :status (if rate :ok :error)))))

(defmethod reg/run-action! :action.fx/rate-failed [_ [_ pair]]
  (let [{:keys [from to]} @state]
    (when (= pair [(currencies from) (currencies to)])
      (swap! state assoc :rate nil :status :error))))

(reg/register-example!
 {:id :fx :title "Live Currency" :view view :state state
  :width 420 :height 210 :on-open (fn [_window] (fetch-rate!))})
