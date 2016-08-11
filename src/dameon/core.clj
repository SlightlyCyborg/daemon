(ns dameon.core
  (require [dameon.voice.core :as voice]
           [dameon.face.core :as face]))


(defn greet [name]
  (face/change-emotion :exuberant)
  (voice/speak (str "Hello " name ))
  (voice/speak (str "How are you?"))
  (face/change-emotion :happy))




















