(ns demo.examples.currency
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [demo.registry :as reg]))

(defonce state (atom {:rate "" :dollars "" :amount nil}))

;; The book's Converter class, in its entirety.
(defn convert [dollars rate] (* dollars rate))

(defn- parse-number
  "Strict decimal parse via edn/read-string — nil for anything else.
  (Portable: no host float-parsing interop assumed.)"
  [s]
  (let [s (str/trim (or s ""))]
    (when (re-matches #"-?[0-9]+(\.[0-9]+)?" s)
      (edn/read-string s))))

(defn- fmt-2dp
  "Two decimal places, the book's display format, without host format/printf."
  [n]
  (let [cents (long (+ 0.5 (* 100.0 n)))]
    (str (quot cents 100) "." (let [c (rem cents 100)]
                                (if (< c 10) (str "0" c) c)))))

(defn view [{:keys [amount]}]
  [:vbox {:spacing 10 :margin 16}
   [:label {:markup [:span {:size "large" :weight "bold"} "Currency Converter"]}]
   [:hbox {:spacing 8}
    [:label {:label "Exchange Rate per $1:"}]
    [:entry {:width-request 120 :on {:change [[:action.currency/set-rate]]}}]]
   [:hbox {:spacing 8}
    [:label {:label "Dollars to Convert:"}]
    [:entry {:width-request 120 :on {:change [[:action.currency/set-dollars]]}}]]
   [:hbox {:spacing 8}
    [:label {:label "Amount in Other Currency:"}]
    ;; The book uses a non-editable text field; a label is the honest
    ;; equivalent here (no :editable prop on :entry in the v1 widget set).
    [:label {:markup [:span {:weight "bold"} (if amount (fmt-2dp amount) "--")]}]]
   [:button {:label "Convert" :on {:click [[:action.currency/convert]]}}]])

(defmethod reg/action-spec :action.currency/set-rate [_]
  (s/cat :kind #{:action.currency/set-rate}))
(defmethod reg/action-spec :action.currency/set-dollars [_]
  (s/cat :kind #{:action.currency/set-dollars}))
(defmethod reg/action-spec :action.currency/convert [_]
  (s/cat :kind #{:action.currency/convert}))

(defmethod reg/run-action! :action.currency/set-rate [event _]
  (swap! state assoc :rate (reg/event-value event)))
(defmethod reg/run-action! :action.currency/set-dollars [event _]
  (swap! state assoc :dollars (reg/event-value event)))
(defmethod reg/run-action! :action.currency/convert [_ _]
  (swap! state (fn [{:keys [rate dollars] :as st}]
                 (let [r (parse-number rate)
                       d (parse-number dollars)]
                   ;; Explicit nil on unparseable input — no exception-driven
                   ;; control flow (the silent-swallow risk upstream flags).
                   (assoc st :amount (when (and r d) (convert d r)))))))

(reg/register-example!
 {:id :currency :title "Currency Converter" :view view :state state
  :width 400 :height 230})
