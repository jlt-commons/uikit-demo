(ns demo.examples.particles
  (:require [clojure.spec.alpha :as s]
            [demo.registry :as reg]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]))

(def ^:private W 360.0)   ; canvas size, fixed — physics and view share it
(def ^:private H 240.0)
(def ^:private GRAVITY 0.35)
(def ^:private DAMPING 0.82)
(def ^:private MAX-PARTICLES 60)

(defn- rand-particle [x y bright?]
  {:x x :y y
   :vx (- (* 6.0 (rand)) 3.0)
   ;; Negative vy = upward (y grows DOWNWARD in the canvas layer — measured,
   ;; see the step docstring). New particles fountain up, then gravity wins.
   :vy (- -2.0 (* 4.0 (rand)))
   :r  (+ 3.0 (* 4.0 (rand)))
   :bright? bright?})

(defn- seed-particles []
  (vec (repeatedly 24 #(rand-particle (* W (rand)) (* H (rand)) false))))

(defonce state (atom {:particles (seed-particles)}))

;; --- physics: pure data in, pure data out ------------------------------------

(defn- step
  "One tick of one particle. Gravity INCREASES y: the canvas layer's origin
  is top-left, y growing downward. (First shipped with the opposite sign on
  the assumption that an unflipped AppKit view's bottom-left origin applied
  — the particles fell UP. The layer tree behind the canvas uses flipped,
  top-left geometry; one measured launch beat the docs.) Walls bounce with
  damping."
  [{:keys [x y vx vy r] :as p}]
  (let [vy (+ vy GRAVITY)
        x' (+ x vx)
        y' (+ y vy)
        [x' vx] (cond (< x' r)        [r (* (- vx) DAMPING)]
                      (> x' (- W r))  [(- W r) (* (- vx) DAMPING)]
                      :else           [x' vx])
        [y' vy] (cond (< y' r)        [r (* (- vy) DAMPING)]
                      (> y' (- H r))  [(- H r) (* (- vy) DAMPING)]
                      :else           [y' vy])]
    (assoc p :x x' :y y' :vx vx :vy vy)))

(defn- step-all [ps] (mapv step ps))

;; --- animation lifecycle -----------------------------------------------------

(defonce ^:private anim (atom {:timer nil :win nil}))

(defn- window-visible? [win]
  ;; BOOL returns cross the FFI as a Scheme character (see glitter-uikit's
  ;; issue #1) — compare against (char 0), not 0.
  (not= (char 0) (u/objc-msg-send-0char win (u/sel "isVisible"))))

(defn- tick! []
  (let [{:keys [timer win]} @anim]
    (if (and win (not (window-visible? win)))
      ;; Window closed: stop ticking. Reopening restarts via :on-open.
      (do (when timer (w/cancel-every! timer))
          (swap! anim assoc :timer nil))
      (swap! state update :particles step-all))))

(defn start!
  "The :on-open hook: remember the window (the tick polls its visibility)
  and start the timer unless one is already running."
  [win]
  (swap! anim assoc :win win)
  (when-not (:timer @anim)
    (swap! anim assoc :timer (w/every! 40 tick!))))

;; --- view --------------------------------------------------------------------

(defn view [{:keys [particles]}]
  [:vbox {:spacing 10 :margin 16}
   [:label {:markup [:span {:size "large" :weight "bold"} "Particle Toy"]}]
   [:label {:markup [:span {:size "small" :foreground "#8e939d"}
                     (str (count particles)
                          " particles · click the canvas to burst")]}]
   [:canvas {:width-request W :height-request H
             :circles (mapv (fn [{:keys [x y r bright?]}]
                              {:x x :y y :r r :selected? bright?})
                            particles)
             :on {:click [[:action.particles/burst]]}}]
   [:hbox {:spacing 8}
    [:button {:label "Seed" :on {:click [[:action.particles/seed]]}}]
    [:button {:label "Clear" :on {:click [[:action.particles/clear]]}}]]])

;; --- actions -----------------------------------------------------------------

(defmethod reg/action-spec :action.particles/burst [_]
  (s/cat :kind #{:action.particles/burst}))
(defmethod reg/action-spec :action.particles/seed [_]
  (s/cat :kind #{:action.particles/seed}))
(defmethod reg/action-spec :action.particles/clear [_]
  (s/cat :kind #{:action.particles/clear}))

(defmethod reg/run-action! :action.particles/burst [event _]
  (let [{:keys [x y]} (reg/event-value event)]
    (when (and x y)
      (swap! state update :particles
             (fn [ps]
               (->> (concat ps (repeatedly 8 #(rand-particle x y true)))
                    (take-last MAX-PARTICLES)
                    vec))))))

(defmethod reg/run-action! :action.particles/seed [_ _]
  (swap! state assoc :particles (seed-particles)))

(defmethod reg/run-action! :action.particles/clear [_ _]
  (swap! state assoc :particles []))

(reg/register-example!
 {:id :particles :title "Particle Toy" :view view :state state
  :width 400 :height 350 :on-open start!})
