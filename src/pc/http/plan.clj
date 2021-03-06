(ns pc.http.plan
  "Provides helpers for plan operations that need to happen during an http request"
  (:require [clj-time.core :as time]
            [clj-time.coerce]
            [pc.analytics :as analytics]
            [pc.models.plan :as plan-model]
            [pc.stripe :as stripe]
            [pc.utils :as utils]))


(defn stripe-customer->plan-fields [stripe-customer]
  (let [card-fields (-> stripe-customer (get-in ["sources" "data"]) first)
        subscription (-> stripe-customer (get-in ["subscriptions" "data"]) first)
        discount-fields (-> stripe-customer (get-in ["discount"]))]
    (merge
     (stripe/card-api->model card-fields)
     {:plan/start (-> subscription
                    (get "start")
                    stripe/timestamp->model)
      :plan/next-period-start (-> subscription
                                (get "current_period_end")
                                stripe/timestamp->model)
      :plan/stripe-subscription-id (get subscription "id")
      :plan/stripe-customer-id (get stripe-customer "id")}
     (when (seq discount-fields)
       (stripe/discount-api->model discount-fields)))))

(defn create-stripe-customer
  "Creates Stripe customer and new subscription from token generated by Checkout.js"
  [team cust token-id]
  (let [plan (:team/plan team)
        stripe-customer (stripe/create-customer token-id
                                                "team"
                                                (or (:plan/trial-end plan)
                                                    (time/now))
                                                :email (:cust/email cust)
                                                :coupon-code (some-> plan
                                                               :discount/coupon
                                                               (plan-model/coupon-read-api)
                                                               :coupon/stripe-id)
                                                :description (format "Team plan for %s, created by %s"
                                                                     (:team/subdomain team)
                                                                     (:cust/email cust))
                                                :quantity (max 1 (count (:plan/active-custs plan))))]
    (utils/straight-jacket
     (analytics/track-create-plan team)) ; non-blocking
    stripe-customer))

(defn update-card
  "Creates Stripe customer and new subscription from token generated by Checkout.js"
  [team token-id]
  (let [plan (:team/plan team)
        stripe-customer (stripe/update-card (:plan/stripe-customer-id plan) token-id)]
    stripe-customer))
