(ns dameon.brochas-area.core
  (:require [dameon.voice.core :as voice]
            [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [dameon.prefrontal-cortex.input :as pfc]))

(import org.httpkit.BytesInputStream
        '[javax.sound.sampled
          Port
          AudioFileFormat
          AudioFormat
          TargetDataLine
          DataLine$Info
          AudioSystem
          AudioInputStream])

(def wave-type javax.sound.sampled.AudioFileFormat$Type/WAVE)



(defn request-new-access-token []
  (let [options
        {:form-params {:grant_type "client_credentials" 
                        :client_id "71ee3462a7574c428a365a43bd4ce0c4" 
                        :client_secret "71ee3462a7574c428a365a43bd4ce0c4" 
                        :scope "https://speech.platform.bing.com"}
         :headers {"Content-type" "application/x-www-form-urlencoded"}}]
    (http/post "https://oxford-speech.cloudapp.net/token/issueToken" options)))

(defn parse-access-token-response-body
  "Returns an map with :access-token (string) and :expiration-time (int, in secs)"
  [access-token-response-body]
  (let [body-map (json/read-str access-token-response-body)]
    {:access-token (get body-map "access_token")
     :expiration-time (+ (System/currentTimeMillis)
                         (* 1000 (Integer/parseInt (get body-map "expires_in"))))}))

;;Access-token is a map with :access-token and :expiration-time
(def access-token (atom nil))
(def initialized (atom false))
(defn set-new-access-token []
  (swap! access-token
         (fn [ignore]
           (parse-access-token-response-body
            (get @(request-new-access-token) :body)))))

;;Get the initial access token
(async/go (set-new-access-token) (swap! initialized (constantly true)))

(defn get-access-token
  "Returns the access token string"
  []
  (if (nil? access-token)
    (set-new-access-token))
  (if (> (System/currentTimeMillis) (get @access-token :expiration-time))
    (set-new-access-token))
  (get @access-token :access-token))

(defn slurp-bytes
  "Slurp the bytes from a slurpable thing"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy (clojure.java.io/input-stream x) out)
    (.toByteArray out)))

(defn stopper-thread [time line]
  (Thread/sleep time)
  (.stop line)
  (.close line))

(defn record-audio
  [time]
  (let [file-name "speech-to-text.wav"
        audio-format (AudioFormat. 16000 8 1 true true)
        line-info (DataLine$Info. TargetDataLine audio-format)]
    (if (not (AudioSystem/isLineSupported line-info))
      (throw (Exception. "Audio line is not supported"))
      (let [line (AudioSystem/getLine line-info)]
        (.open line audio-format)
        (.start line)
        (async/go (stopper-thread time line))
        (with-open [out (java.io.ByteArrayOutputStream.)]
         (AudioSystem/write
          (AudioInputStream. line)
          wave-type
          (java.io.File. file-name)))))
    (slurp-bytes file-name)))

(defn interpret-speech [sound-bytes]
  (let [options 
        {:headers {"Authorization" (str "Bearer " (get-access-token))
                   "Content-type" "audio/wav; codec=\"audio/pcm\"; samplerate=16000"}
         :query-params {"scenarios" "smd"
                        "appid" "D4D52672-91D7-4C74-8AD8-42B1D98141A5" 
                        "locale"  "en-US"
                        "device.os" "MacOSx"
                        "version"  "3.0"
                        "format" "json"
                        "instanceid" "61f3d2ab-d2da-4ceb-b89d-e92c6b5d2183"
                        "requestid" "98d31a6b-fbe3-4bbf-89cd-79287632315b"}
         :body (BytesInputStream. sound-bytes (count sound-bytes))}]
    (http/post "https://speech.platform.bing.com/recognize" options)))

(defn get-words-from-api-result [api-result]
  (get-in (json/read-str (get api-result :body)) ["results" 0 "name"]))


(defn record-and-interpret-speech [time]
  (pfc/input
   (let [sound-bytes (record-audio time)]
     {:event :words
      :from  :api
      :data  (get-words-from-api-result
              @(interpret-speech sound-bytes))})))



(defn readFile [file listener]
  (with-open [rdr (clojure.java.io/reader file)]
    (doseq [line (line-seq rdr)]
      (listener line))))

(defn launch-sphinx-dameon []
  (async/go (shell/sh "sh" "src/dameon/brochas_area/launch_sphinx.sh")))

(defn kill-sphinx-dameon []
  (doall
   (map #(shell/sh "kill" "-9" %)
        (clojure.string/split
         (get (shell/sh "src/dameon/brochas_area/get_pid.sh") :out)
         #"\n"))))

(defn sphinx-listener [line]
  (println line)
  (if (> (.indexOf line "ok dag knee") -1)
    (pfc/input  {:event :words
                 :from  :sphinx
                 :data  "ok dagny"} )))

(defn launch-sphinx-listener []
  (async/go
    (readFile
    (str (System/getProperty "user.dir") "/src/dameon/brochas_area/pipe")
    sphinx-listener)))

(defn launch-sphinx []
  (launch-sphinx-dameon)
  (launch-sphinx-listener))










