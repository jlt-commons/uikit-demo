(ns demo.examples.counter
  (:require [clojure.spec.alpha :as s]
            [demo.registry :as reg]))

(defonce state (atom {:count 0}))

(defn view [{:keys [count]}]
  [:vbox {:spacing 12 :margin 16}
   [:label {:markup [:span {:size "small" :foreground "#8e939d"} "COUNT"]}]
   [:label {:markup [:span {:size "xx-large" :weight "bold"} (str count)]}]
   [:hbox {:spacing 8 :homogeneous true}
    [:button {:label "+ 1" :on {:click [[:action/inc]]}}]
    [:button {:label "Reset" :on {:click [[:action/reset]]}}]
    [:button {:label "- 1" :on {:click [[:action/dec]]}}]]])

(defmethod reg/action-spec :action/inc [_] (s/cat :kind #{:action/inc}))
(defmethod reg/action-spec :action/dec [_] (s/cat :kind #{:action/dec}))
(defmethod reg/action-spec :action/reset [_] (s/cat :kind #{:action/reset}))

(defmethod reg/run-action! :action/inc [_ _] (swap! state update :count inc))
(defmethod reg/run-action! :action/dec [_ _] (swap! state update :count dec))
(defmethod reg/run-action! :action/reset [_ _] (swap! state assoc :count 0))

(reg/register-example!
 {:id :counter :title "Counter" :view view :state state
  :width 300 :height 170})
