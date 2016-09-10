(ns dameon.face.core
  (require
   [dameon.face.emotion-animation :as animation]
   [dameon.settings :as settings]
   [dameon.smart-atom :as smart-atom]
   [quil.core :as q]
   [quil.middleware :as m]
   [clojure.core.async :as async :refer [go chan <! >!]]))

(import '[org.opencv.core MatOfInt MatOfByte MatOfRect Mat CvType Size]
        '[org.opencv.imgcodecs Imgcodecs]
        '[org.opencv.imgproc Imgproc])


(import 'java.nio.ByteBuffer)
(import 'java.nio.ByteOrder)

(def width settings/width)
(def height settings/height)

(def transitioner (ref nil))
(def draw-mat? (ref false))
(def mat-to-draw (ref (Mat. width height CvType/CV_8UC3)))
(def in-main-loop? (ref false))


(defn load-emotions []
  (apply merge (map
    (fn [emot] {emot (animation/new emot)})
    settings/available-emotions)))

(defn setup []
  (q/frame-rate 1)
  (q/background 255)
  (let [anim (q/load-image "face_animations/Happy/neutral_to_emotion.png")]
    (q/resize anim  (* 4 width) height)
    {:x 1 :animation anim :emotions (load-emotions) :cur-index 0 :transition-emotion :happy :cur-emotion :happy}))

(defn update-state [state]
  (q/frame-rate 10)
  (if (not (nil? @transitioner))
    (let [transition-emotion @transitioner]
      (do
       (dosync (ref-set transitioner nil))
       (assoc
        (assoc-in state
                  [:emotions (state :cur-emotion)]
                  (animation/transition-out-of (get-in state [:emotions (state :cur-emotion)])))
        :transition-emotion transition-emotion)))
    (if (not ((get-in state [:emotions (state :cur-emotion)]) :finished))
      (do
        (if (= (get-in state [:emotions (state :cur-emotion) :cur-animation]) :emotion-loop)
          (dosync (ref-set in-main-loop? true)))
        (assoc-in state
                [:emotions (state :cur-emotion)]
                (animation/next-frame (get-in state [:emotions (state :cur-emotion)]))))
     (assoc
      (assoc-in state
                [:emotions (state :transition-emotion)]
                (animation/transition-into (get-in state [:emotions (state :transition-emotion)])))
       :cur-emotion
       (state :transition-emotion)))))

(defn mat-to-p-image [mat]
  (let [w (.width mat)
        h (.height mat)
        mat2 (Mat.)
        image (q/create-image w h :rgb)
        data8 (make-array Byte/TYPE (* w h 4))
        data32 (int-array (* w h))]
    (. Imgproc cvtColor mat mat2 Imgproc/COLOR_RGB2RGBA)
    (.loadPixels image)
    (.get mat2 0 0 data8)
    (.get (.asIntBuffer (.order (. ByteBuffer wrap data8) ByteOrder/LITTLE_ENDIAN)) data32)
    ;(set! (.-pixels image) data32)
    (System/arraycopy data32 0 (.-pixels image) 0 (count data32))
    (.updatePixels image)
    image))

(defn draw-mat [mat]
  (q/image (mat-to-p-image mat) 0 0 ))

(defn update-mat-to-display [smart-mat]
  (try
    (let [mat
         (if (= (.size (smart-atom/deref smart-mat))
                (Size. width height))
           (.clone (deref smart-mat))
           (let [tmp-mat (.clone (smart-atom/deref smart-mat))]
             (. Imgproc resize (smart-atom/deref smart-mat) tmp-mat (Size. width height))
             tmp-mat))]
      (smart-atom/delete smart-mat)
      (let [old-mat @mat-to-draw]
        (dosync (ref-set mat-to-draw mat))
        (.release old-mat)))
    (catch Exception e (println (str (.getMessage e))))))

(defn activate-mat-display []
  (dosync (ref-set draw-mat? true)))

(defn deactivate-mat-display []
  (dosync (ref-set draw-mat? false)))


(defn draw [state]
  (q/background 0)
  (if @draw-mat?
    (draw-mat @mat-to-draw)
    (q/image (animation/get-cur-frame (get-in state [:emotions (state :cur-emotion)]))  0 0))
  (q/fill 255))
  
;(go (>! "Hello there"))

(defn create []
 (q/defsketch dameon-face
   :size [width height]
   :setup setup
   :update update-state
   :draw draw
   :middleware [m/fun-mode]))



(defn change-emotion
  "Function used to change the emotion displayed of the face. 
   If keyword argument :block is set true, then the function will wait until the animation is in the :emotion-loop to return"
  [emotion & {:keys [block] :or {block false}}]
  (dosync (ref-set in-main-loop? false))
  (if (> (.indexOf settings/available-emotions emotion) -1)
    (dosync (ref-set transitioner emotion))
    (throw (Exception. "This emotion is not available")))
  (if block
    (while (not @in-main-loop?))))


