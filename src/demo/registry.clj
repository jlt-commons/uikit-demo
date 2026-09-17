(ns demo.registry
  "Open seams: the example registry and the extensible action dispatcher.
  Example namespaces require this namespace only — never demo.core."
  (:require [clojure.spec.alpha :as s]
            [glitter.core :as core]))

;; --- the example registry ----------------------------------------------------
(s/def ::id keyword?)
(s/def ::title string?)
(s/def ::view fn?)
(s/def ::state some?)          ; a derefable state atom (no portable Atom class check)
(s/def ::width pos-int?)
(s/def ::height pos-int?)
(s/def ::on-open fn?)   ; optional: (fn [window-ptr]), runs on every open/reopen
(s/def ::example (s/keys :req-un [::id ::title ::view ::state ::width ::height]
                         :opt-un [::on-open]))

(defonce examples (atom []))

(defn register-example!
  "Add (or replace, by :id) an example. Conforms before anything is built."
  [ex]
  (when-not (s/valid? ::example ex)
    (throw (ex-info (str "Invalid example: " (s/explain-str ::example ex))
                    {:example ex})))
  (swap! examples (fn [xs] (conj (vec (remove #(= (:id %) (:id ex)) xs)) ex)))
  nil)

;; --- the open action pipeline ------------------------------------------------
(defmulti action-spec
  "Spec for one action vector, dispatched on its kind. Each example
  defmethods its own kinds; no method means invalid — typos stay loud."
  first)

(s/def ::action (s/multi-spec action-spec (fn [g _] g)))
(s/def ::actions (s/coll-of ::action))

(defn event-value
  "The value carried by an event — an entry's text, a checkbox state, a
  drop-down index. The renderer puts :glitter/value in its RAW event map, but
  glitter.core/build-event-map nests that whole map under :glitter/dom-event
  (lifting only :glitter/node), so the value is one level down from where
  you'd look first. Upstream's temperature.clj documents the same finding and
  resolves it with an identical get-in in its nexus placeholder."
  [event]
  (get-in event [:glitter/dom-event :glitter/value]))

(defmulti run-action!
  "Interpret one action vector. `event` is glitter's wrapped event map —
  read values with `event-value`, not (:glitter/value event)."
  (fn [_event action] (first action)))

(defmethod run-action! :default [_ action]
  (println "Unhandled action:" (pr-str action)))

(defn execute-actions [event actions]
  (when-not (s/valid? ::actions actions)
    (println "Invalid actions:" (s/explain-str ::actions actions)))
  (doseq [a actions] (run-action! event a)))

(core/set-dispatch! execute-actions)
