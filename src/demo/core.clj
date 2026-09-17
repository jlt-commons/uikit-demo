(ns demo.core
    (:require [clojure.spec.alpha :as s]
              [demo.registry :as reg]
              [demo.examples.counter]
              [demo.examples.currency]
              [demo.examples.fx]
              [demo.examples.particles]
              [glitter-uikit.app :as app]
              [glitter-uikit.appkit :as appkit]
              [glitter-uikit.ffi :as u]
              [glitter-uikit.widget :as w]
              [glitter.alias :as alias]
              [glitter.core :as core]
              [jolt.ffi :as ffi]))

(defonce state (atom {:bg nil}))

(defn hub-view [_state]
  (into [:vbox {:spacing 8 :margin 16}
         [:label {:markup [:span {:size "large" :weight "bold"} "glitter-uikit examples"]}]
         [:label {:markup [:span {:size "small" :foreground "#8e939d"}
                           "Each example opens in its own window."]}]]
        (for [{:keys [id title]} @reg/examples]
          [:button {:label title :on {:click [[:action/open-example id]]}}])))

;; CoreFoundation bindings glitter-uikit.ffi doesn't carry.
(ffi/defcfn cf-bundle-get-main-bundle  "CFBundleGetMainBundle"      []         :pointer)
(ffi/defcfn cf-bundle-info-dictionary  "CFBundleGetInfoDictionary"  [:pointer] :pointer)
(ffi/defcfn cf-dictionary-set-value    "CFDictionarySetValue"       [:pointer :pointer :pointer] :void)

(defn set-app-name!
  "Seed CFBundleName in the main bundle's live info dictionary so the app
  menu's title reads `name` instead of the process name. Must run before
  NSApplication initializes — i.e. before app/run."
  [name]
  (let [dict (cf-bundle-info-dictionary (cf-bundle-get-main-bundle))]
    (cf-dictionary-set-value dict (u/nsstring "CFBundleName") (u/nsstring name))))

;; NSEventModifierFlags — key-equivalent modifiers. ⌘ is the implicit default.
(def MOD-SHIFT   (bit-shift-left 1 17))
(def MOD-CONTROL (bit-shift-left 1 18))
(def MOD-OPTION  (bit-shift-left 1 19))
(def MOD-COMMAND (bit-shift-left 1 20))

;; Menu items conform BEFORE any FFI call touches them: an invalid spec fails
;; at install time with an explain, not as a grayed-out item or a segfault.
(s/def ::title string?)
(s/def ::action string?)                          ; ObjC selector name
(s/def ::handler fn?)
(s/def ::key (s/and string? #(= 1 (count %))))    ; single-char key equivalent
(s/def ::modifiers nat-int?)
(s/def ::menu-item
  (s/and (s/keys :req-un [::title] :opt-un [::action ::handler ::key ::modifiers])
         ;; an item answers to the responder chain OR to Clojure — not both
         (fn [{:keys [action handler]}] (not (and action handler)))))
(s/def ::menu-items (s/coll-of (s/or :separator #{:separator}
                                     :item ::menu-item)))

(defn- menu-item
  "Realize one item spec into an NSMenuItem.
  {:title \"...\"                ; required
   :action \"selectorName:\"     ; ObjC selector, nil target -> responder chain
   :handler (fn [] ...)          ; OR a Clojure fn, via the fire: registry
   :key \"q\"                    ; key equivalent (default modifier: ⌘)
   :modifiers MOD-...}           ; bit-or of MOD-*, when ⌘ alone is wrong"
  [{:keys [title action handler key modifiers]}]
  (let [sel-name (cond action  action
                       handler "fire:"
                       :else   nil)
        item (u/objc-msg-send-3p (u/objc-msg-send-0 (u/cls "NSMenuItem") (u/sel "alloc"))
                                 (u/sel "initWithTitle:action:keyEquivalent:")
                                 (u/nsstring title)
                                 (if sel-name (u/sel sel-name) ffi/null)
                                 (u/nsstring (or key "")))]
    (when handler
      (u/objc-msg-send-1pvoid item (u/sel "setTarget:") w/invoker)
      (swap! w/actions assoc-in [item :click] (fn [_sender] (handler))))
    (when modifiers
      (u/objc-msg-send-1i64void item (u/sel "setKeyEquivalentModifierMask:") modifiers))
    item))

(defn make-main-menu!
  "Install a main menu whose app menu is built from `items` — a sequence of
  item-spec maps (see `menu-item`) and :separator keywords.
  Throws with an explain when `items` doesn't conform to ::menu-items."
  [app-name items]
  (when-not (s/valid? ::menu-items items)
    (throw (ex-info (str "Invalid menu items: " (s/explain-str ::menu-items items))
                    {:items items})))
  (let [menubar  (u/objc-msg-send-0 (u/cls "NSMenu") (u/sel "new"))
        app-item (u/objc-msg-send-0 (u/cls "NSMenuItem") (u/sel "new"))
        app-menu (u/objc-msg-send-1p (u/objc-msg-send-0 (u/cls "NSMenu") (u/sel "alloc"))
                                     (u/sel "initWithTitle:") (u/nsstring app-name))]
    (doseq [spec items]
      (u/objc-msg-send-1pvoid app-menu (u/sel "addItem:")
                              (if (= :separator spec)
                                (u/objc-msg-send-0 (u/cls "NSMenuItem") (u/sel "separatorItem"))
                                (menu-item spec))))
    (u/objc-msg-send-1pvoid app-item (u/sel "setSubmenu:") app-menu)
    (u/objc-msg-send-1pvoid menubar (u/sel "addItem:") app-item)
    (u/objc-msg-send-1pvoid (u/shared-application) (u/sel "setMainMenu:") menubar)))

(declare show-preferences!)  ; defined in the Preferences section below

(defn app-menu-items
  "The standard Mac app menu, as data."
  [app-name]
  [{:title (str "About " app-name) :action "orderFrontStandardAboutPanel:"}
   :separator
   {:title "Preferences…" :key ","
    :handler (fn [] (show-preferences!))}
   :separator
   {:title (str "Hide " app-name) :action "hide:" :key "h"}
   {:title "Hide Others" :action "hideOtherApplications:" :key "h"
    :modifiers (bit-or MOD-OPTION MOD-COMMAND)}
   {:title "Show All" :action "unhideAllApplications:"}
   :separator
   {:title (str "Quit " app-name) :action "terminate:" :key "q"}])

(defn mount-second!
  "appkit/mount! hardcodes its add-watch key, so a second mount! on the same
  atom would replace the first window's re-render watch. Identical wiring,
  caller-supplied key."
  [window view state-atom watch-key]
  (let [r (appkit/renderer)
        root-el (atom {:tag :window :view window :children [] :handlers {}})
        vdom (atom nil)
        render! (fn [st]
                  (reset! vdom (:vdom (core/reconcile r root-el (view st) @vdom
                                                      {:aliases (alias/get-registered-aliases)}))))]
    (render! @state-atom)
    (add-watch state-atom watch-key (fn [_ _ _ st] (app/on-gui (fn [] (render! st)))))
    nil))

(defn- nscolor->rgba [c]
  (let [srgb (u/objc-msg-send-1p c (u/sel "colorUsingColorSpace:")
                                 (u/objc-msg-send-0 (u/cls "NSColorSpace")
                                                    (u/sel "sRGBColorSpace")))]
    (when-not (ffi/null? srgb)
      [(u/objc-msg-send-0d srgb (u/sel "redComponent"))
       (u/objc-msg-send-0d srgb (u/sel "greenComponent"))
       (u/objc-msg-send-0d srgb (u/sel "blueComponent"))
       (u/objc-msg-send-0d srgb (u/sel "alphaComponent"))])))

(defn- rgba->nscolor [[r g b a]]
  (u/objc-msg-send-4d (u/cls "NSColor")
                      (u/sel "colorWithSRGBRed:green:blue:alpha:") r g b a))

(defn open-color-panel!
  "Order front the shared NSColorPanel, wired to dispatch :action/set-bg
  continuously as the color changes."
  []
  (let [panel (u/objc-msg-send-0 (u/cls "NSColorPanel") (u/sel "sharedColorPanel"))]
    (swap! w/actions assoc-in [panel :click]
           (fn [sender]
             (when-let [rgba (nscolor->rgba (u/objc-msg-send-0 sender (u/sel "color")))]
               (reg/execute-actions nil [[:action/set-bg rgba]]))))
    (u/objc-msg-send-1intvoid panel (u/sel "setContinuous:") 1)
    (u/objc-msg-send-1pvoid panel (u/sel "setTarget:") w/invoker)
    (u/objc-msg-send-1pvoid panel (u/sel "setAction:") (u/sel "fire:"))
    (u/objc-msg-send-1pvoid panel (u/sel "makeKeyAndOrderFront:") ffi/null)))

(s/def ::rgba (s/coll-of number? :count 4))

(defmethod reg/action-spec :action/pick-bg [_]
  (s/cat :kind #{:action/pick-bg}))
(defmethod reg/action-spec :action/set-bg [_]
  (s/cat :kind #{:action/set-bg} :color ::rgba))

(defmethod reg/run-action! :action/pick-bg [_ _] (open-color-panel!))
(defmethod reg/run-action! :action/set-bg [_ [_ rgba]]
  (swap! state assoc :bg rgba))

(defn- place-near-pointer!
  "Put `win`'s top-left just below-right of the current pointer position —
  where the user's attention already is. NSPoint arg = two flattened doubles."
  [win]
  (let [[mx my] (u/mouse-location)]
    (u/objc-msg-send-2dvoid win (u/sel "setFrameTopLeftPoint:")
                            (+ mx 24.0) (- my 16.0))))

(defonce ^:private prefs-window (atom nil))

(defn prefs-view [{:keys [bg]}]
  [:vbox {:spacing 10 :margin 16}
   [:label {:markup [:span {:weight "bold"} "Background color"]}]
   [:label {:markup [:span {:foreground "#8e939d"}
                     (if bg (pr-str (mapv #(/ (int (* 100 %)) 100.0) bg))
                         "system default")]}]
   [:button {:label "Choose…" :on {:click [[:action/pick-bg]]}}]])

(defn show-preferences! []
  (if-let [win @prefs-window]
    (u/window-show! win)
    (let [win (u/window-new "Preferences" 280 140)]
      (u/objc-msg-send-1intvoid win (u/sel "setReleasedWhenClosed:") 0)
      (mount-second! win prefs-view state ::prefs-render)
      (reset! prefs-window win)
      (place-near-pointer! win)
      (u/window-show! win))))

(defn watch-background!
  "Apply :bg to `window` now and on every state change (no-op while nil)."
  [window state-atom]
  (let [apply! (fn [{:keys [bg]}]
                 (when bg
                   (u/objc-msg-send-1pvoid window (u/sel "setBackgroundColor:")
                                           (rgba->nscolor bg))))]
    (apply! @state-atom)
    (add-watch state-atom ::apply-bg (fn [_ _ _ st] (app/on-gui (fn [] (apply! st)))))))

(defonce ^:private example-windows (atom {}))

(defmethod reg/action-spec :action/open-example [_]
  (s/cat :kind #{:action/open-example} :id keyword?))

(defmethod reg/run-action! :action/open-example [_ [_ id]]
  (let [{:keys [title view state width height on-open] :as ex}
        (first (filter #(= id (:id %)) @reg/examples))]
    (when ex
      (let [win (or (@example-windows id)
                    (let [win (u/window-new title width height)]
                      (u/objc-msg-send-1intvoid win (u/sel "setReleasedWhenClosed:") 0)
                      (mount-second! win view state (keyword "demo.example" (name id)))
                      (swap! example-windows assoc id win)
                      (place-near-pointer! win)
                      win))]
        (u/window-show! win)
        ;; :on-open runs on every open AND reopen, and receives the window
        ;; pointer — Live Currency refetches rates; the Particle Toy starts
        ;; its animation timer and uses the window to know when to stop.
        (when on-open (on-open win))))))

(defn -main [& _]
  (set-app-name! "Demo")
  (app/run
    (fn [window]
      (make-main-menu! "Demo" (app-menu-items "Demo"))
      (u/objc-msg-send-1intvoid window (u/sel "setTitlebarAppearsTransparent:") 1)
      ;; (add-titlebar-button! window "Reset" (fn [] (reset! state {:count 0})))
      (watch-background! window state)
      (appkit/mount! window hub-view state))
    :title "Demo"
    :width 300
    :height 220))
