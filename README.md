# mjolnir

<img src="docs/illustration.png" width="300" alt="Sisyphus" align="right">

A fail2ban-like Ring middleware for Clojure web apps.

It bans clients after a configurable number of failed (non-2xx) requests, with optional persistence to disk.

## Usage

```clojure
(require '[mjolnir.core :as mjolnir])

(defonce mjolnir-context
  (mjolnir/make-context
   {:mjolnir.opts/max-strikes       5
    :mjolnir.opts/ttl               3600000  ;; 1 hour in ms
    :mjolnir.opts/storage-directory "data/mjolnir"
    :mjolnir.opts/factors           {:ip      :remote-addr
                                     :user-id (fn [req] (get-in req [:session :user-id]))}}))

;; On startup: restore previously persisted bans
(mjolnir/load! mjolnir-context)

;; Wrap your handler
(def app (mjolnir/middleware mjolnir-context my-handler))

;; On shutdown (or periodically): persist bans to disk
(mjolnir/persist! mjolnir-context)
```

After `max-strikes` number of non-2xx responses for a given factor (IP address, user ID, etc.), that client receives `400 Banned` on all subsequent requests. The failure count resets after `ttl` milliseconds of inactivity.

