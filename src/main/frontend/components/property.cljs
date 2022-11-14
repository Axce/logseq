(ns frontend.components.property
  "Block properties management."
  (:require [frontend.ui :as ui]
            [frontend.util :as util]
            [clojure.string :as string]
            [frontend.handler.property :as property-handler]
            [frontend.db :as db]
            [rum.core :as rum]
            [frontend.state :as state]
            [goog.dom :as gdom]
            [frontend.search :as search]
            [frontend.components.search.highlight :as highlight]
            [frontend.components.svg :as svg]
            [frontend.modules.shortcut.core :as shortcut]
            [medley.core :as medley]))

(defn- add-property
  [entity k *new-property?]
  (when-not (string/blank? k)
    (property-handler/add-property! (:db/id entity) k)
    (reset! *new-property? false)))

(rum/defc search-item-render
  [search-q content]
  [:div.font-medium
   (highlight/highlight-exact-query content search-q)])

(rum/defcs property-input <
  (shortcut/disable-all-shortcuts)
  (rum/local nil ::q)
  [state entity *new-property?]
  (let [*q (::q state)
        result (when-not (string/blank? @*q)
                 (search/property-search @*q))]
    [:div
     [:div.ls-property-add.grid.grid-cols-4.flex.flex-row.items-center
      [:input#add-property.form-input.simple-input.block.col-span-1.focus:outline-none
       {:placeholder "Property key"
        :auto-focus true
        :on-change (fn [e]
                     (reset! *q (util/evalue e)))
        :on-blur (fn [_e]
                   (add-property entity @*q *new-property?))
        :on-key-up (fn [e]
                     (case (util/ekey e)
                       "Enter"
                       (add-property entity @*q *new-property?)

                       "Escape"
                       (reset! *new-property? false)

                       nil))}]
      [:a.close {:on-mouse-down #(do
                                   (reset! *q nil)
                                   (reset! *new-property? false))}
       svg/close]]
     (ui/auto-complete
      result
      {:class "search-results"
       :on-chosen #(add-property entity % *new-property?)
       :item-render #(search-item-render @*q %)})]))

(rum/defcs property-key < (rum/local false ::show-close?)
  [state entity k page-cp property-id ref-property?]
  (let [*show-close? (::show-close? state)]
    [:div.relative
     {:on-mouse-over (fn [_] (reset! *show-close? true))
      :on-mouse-out (fn [_] (reset! *show-close? false))}
     (page-cp {} {:block/name k})
     (when (and @*show-close? (not ref-property?))
       [:div.absolute.top-0.right-0
        [:a.fade-link.fade-in.py-2.px-1
         {:title "Remove this property"
          :on-click (fn [_e]
                      (property-handler/delete-property! entity property-id))}
         (ui/icon "x")]])]))

(rum/defcs multiple-value-item < (rum/local false ::show-close?)
  [state entity property item dom-id' editor-id' {:keys [edit-fn page-cp inline-text]}]
  (let [*show-close? (::show-close? state)
        object? (= "object" (:type (:block/property-schema property)))]
    [:div.flex.flex-1.flex-row {:on-mouse-over #(reset! *show-close? true)
                                :on-mouse-out  #(reset! *show-close? false)}
     [:div.flex.flex-1.property-value-content
      {:id dom-id'
       :on-click (fn [] (edit-fn editor-id' dom-id' item))}
      (if object?
        (page-cp {} {:block/name (util/page-name-sanity-lc item)})
        (inline-text {} :markdown (str item)))]
     (when @*show-close?
       [:a.close.fade-in
        {:title "Delete this value"
         :on-mouse-down
         (fn []
           (property-handler/delete-property-value! entity (:block/uuid property) item))}
        svg/close])]))

(rum/defcs property-value < rum/reactive
  [state entity property k v k' {:keys [inline-text editor-box page-cp]}]
  (let [block (assoc entity :editing-property property)
        dom-id (str "ls-property-" k)
        editor-id (str "property-" (:db/id entity) "-" k')
        editing? (state/sub [:editor/editing? editor-id])
        schema (:block/property-schema property)
        edit-fn (fn [editor-id id v]
                  (let [v (str v)
                        cursor-range (util/caret-range (gdom/getElement (or id dom-id)))]
                    (state/set-editing! editor-id v block cursor-range)
                    (js/setTimeout
                     (fn []
                       (state/set-editor-action-data! {:property (:block/original-name property)
                                                       :entity entity
                                                       :pos 0})
                       (state/set-editor-action! :property-value-search))
                     50)))
        multiple-values? (:multiple-values? schema)
        type (:type schema)]
    (cond
      multiple-values?
      (let [v' (if (coll? v) v (when v [v]))
            v' (if (seq v') v' [""])
            editor-id' (str editor-id (count v'))
            new-editing? (state/sub [:editor/editing? editor-id'])]
        [:div.flex.flex-1.flex-col
         [:div.flex.flex-1.flex-col
          (for [[idx item] (medley/indexed v')]
            (let [dom-id' (str dom-id "-" idx)
                  editor-id' (str editor-id idx)
                  editing? (state/sub [:editor/editing? editor-id'])]
              (if editing?
                (editor-box {:format :markdown
                             :block block} editor-id' {})
                (multiple-value-item entity property item dom-id' editor-id' {:page-cp page-cp
                                                                              :edit-fn edit-fn
                                                                              :inline-text inline-text}))))

          (let [fv (first v')]
            (when (and (not new-editing?)
                       fv
                       (or (and (string? fv) (not (string/blank? fv)))
                           (and (not (string? fv)) (some? fv))))
             [:div.rounded-sm.ml-1
              {:on-click (fn []
                           (edit-fn (str editor-id (count v')) nil ""))}
              [:div.flex.flex-row
               [:div.block {:style {:height      20
                                    :width       20}}
                [:a.add-button-link.block {:title "Add another value"
                                           :style {:margin-left -4}}
                 (ui/icon "circle-plus")]]]]))]
         (when new-editing?
           (editor-box {:format :markdown
                        :block block} editor-id' {}))])

      editing?
      (editor-box {:format :markdown
                   :block block} editor-id {})

      :else
      [:div.flex.flex-1.property-value-content
       {:id dom-id
        :on-click (fn []
                    (edit-fn editor-id nil v))}
       (cond
         (and (= type "date") (string/blank? v))
         [:div "TBD (date icon)"]

         :else
         (when-not (string/blank? (str v))
           (inline-text {} :markdown (str v))))])))

(rum/defcs properties-area <
  (rum/local false ::new-property?)
  rum/reactive
  [state entity properties refs-properties {:keys [page-cp inline-text]}]
  (let [*new-property? (::new-property? state)
        editor-box (state/get-component :editor/box)
        ref-keys (set (keys refs-properties))
        page? (:block/name entity)]
    [:div.ls-properties-area
     (when (seq properties)
       [:div
        (for [[k v] properties]
          (when-let [property (db/pull [:block/uuid k])]
            (when-let [k' (:block/original-name property)]
              (let [ref-property? (contains? ref-keys k)]
                [:div.grid.grid-cols-4.gap-1
                 [:div.property-key.col-span-1
                  (property-key entity k' page-cp k ref-property?)]

                 [:div.col-span-3
                  (property-value entity property k v k' {:page-cp page-cp
                                                          :inline-text inline-text
                                                          :editor-box editor-box})]]))))])

     (when page?
       (if @*new-property?
         (property-input entity *new-property?)
         [:div.flex-1.flex-col.rounded-sm
          {:on-click (fn []
                       (reset! *new-property? true))}
          [:div.flex.flex-row
           [:div.block {:style {:height      20
                                :width       20}}
            [:a.add-button-link.block {:title "Add another property"
                                       :style {:margin-left -4}}
             (ui/icon "circle-plus")]]]]))]))

(defn properties
  [entity block-components-m]
  (let [namespace (:block/namespace entity)
        namespace-properties (when namespace
                               (:block/properties (db/entity (:db/id namespace))))
        properties (merge
                    namespace-properties
                    (:block/properties entity))]
    (properties-area entity properties namespace-properties block-components-m)))

(rum/defc composed-properties < rum/reactive
  [entity refs block-components-m]
  (let [namespaces (map :block/namespace (distinct refs))
        refs-properties (map
                          (fn [ref]
                            (:block/properties
                             (db/pull-block (:db/id ref))))
                          (concat namespaces refs))
        property-maps (concat refs-properties
                              [(:block/properties entity)])
        properties (apply merge property-maps)
        refs-properties' (apply merge refs-properties)]
    (properties-area entity properties refs-properties' block-components-m)))