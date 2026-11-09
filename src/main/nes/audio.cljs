(ns nes.audio
  "Playing the emulator's sound through the Web Audio API.

  The emulator makes one sample at a time, in bursts: about 800 samples
  each time it runs a frame, 60 times a second. The sound card wants a
  steady stream. In between sits an AudioWorklet (code that runs on the
  browser's audio thread) holding a small queue:

    emulator --samples--> `on-sample!` --chunks of 1024--> worklet queue --> speakers

  If the queue runs dry (paused, or the page is busy) the worklet plays
  silence. If it grows too long (emulation running a little fast), the
  oldest audio is dropped, so the sound never lags far behind the picture.

  Browsers only allow sound to start in response to a click or key press,
  so `enable!` has to be called from one."
  (:require [nes.emulator :as emu]))

(def ^:private chunk-size 1024)

(def ^:private worklet-source
  "The audio-thread side. Plain JavaScript, loaded from a Blob URL."
  "
class NesAudio extends AudioWorkletProcessor {
  constructor() {
    super();
    this.queue = [];     // chunks waiting to be played
    this.queued = 0;     // samples in the queue
    this.current = null; // the chunk being played
    this.pos = 0;
    // Keep at most ~120 ms queued: enough to smooth over bursts,
    // short enough that sound stays in step with the picture.
    this.maxQueued = Math.floor(sampleRate * 0.12);
    this.port.onmessage = (e) => {
      if (e.data === 'clear') {
        this.queue = []; this.queued = 0; this.current = null;
        return;
      }
      this.queue.push(e.data);
      this.queued += e.data.length;
      while (this.queued > this.maxQueued && this.queue.length > 1) {
        this.queued -= this.queue.shift().length;
      }
    };
  }
  process(inputs, outputs) {
    const out = outputs[0];
    const left = out[0];
    for (let i = 0; i < left.length; i++) {
      if (!this.current || this.pos >= this.current.length) {
        this.current = this.queue.shift() || null;
        this.pos = 0;
        if (this.current) this.queued -= this.current.length;
      }
      left[i] = this.current ? this.current[this.pos++] : 0;
    }
    for (let c = 1; c < out.length; c++) out[c].set(left);
    return true;
  }
}
registerProcessor('nes-audio', NesAudio);
")

;; {:ctx AudioContext, :node AudioWorkletNode, :gain GainNode} once enabled
(defonce ^:private audio (atom nil))

;; Samples collected so far for the next chunk
(defonce ^:private pending (atom {:buf (js/Float32Array. chunk-size) :n 0}))

;; A high-pass ('DC blocking') filter. The emulator's output can sit at a
;; constant level away from zero, and jumps when the game starts or stops
;; a sound channel, which a speaker turns into pops. The real NES has
;; high-pass filters on its audio output for the same reason (see
;; https://www.nesdev.org/wiki/APU_Mixer). This one removes everything
;; below about 40 Hz:   y[n] = x[n] - x[n-1] + r * y[n-1]
(def ^:private filter-r 0.995)
(defonce ^:private filter-state #js {:x 0 :y 0})

(defn- high-pass [x]
  (let [^js st filter-state
        y (+ (- x (.-x st)) (* filter-r (.-y st)))]
    (set! (.-x st) x)
    (set! (.-y st) y)
    y))

(defn enabled? [] (some? (:node @audio)))

(defn on-sample!
  "The emulator's audio sink: collect a sample, and send a chunk to the
  worklet when there's enough."
  [left right]
  (when-let [^js node (:node @audio)]
    (let [p @pending
          ^js buf (:buf p)
          n (:n p)]
      (aset buf n (high-pass (* 0.5 (+ left right))))
      (if (= (inc n) chunk-size)
        (do
          ;; hand the chunk over (without copying) and start a new one
          (.postMessage (.-port node) buf #js [(.-buffer buf)])
          (reset! pending {:buf (js/Float32Array. chunk-size) :n 0}))
        (swap! pending assoc :n (inc n))))))

(defn set-volume!
  "Volume from 0.0 to 1.0."
  [v]
  (when-let [^js gain (:gain @audio)]
    (set! (.. gain -gain -value) v)))

(defn clear!
  "Throw away queued sound (after pausing, so it doesn't play on resume)."
  []
  (when-let [^js node (:node @audio)]
    (.postMessage (.-port node) "clear")))

(defn enable!
  "Start sound. Call from a click or key handler. Returns a promise."
  [volume]
  (if-let [^js ctx (:ctx @audio)]
    (.resume ctx)
    (let [ctx (js/AudioContext. #js {:sampleRate emu/sample-rate})
          url (js/URL.createObjectURL (js/Blob. #js [worklet-source]
                                                #js {:type "application/javascript"}))]
      (-> (.addModule (.-audioWorklet ctx) url)
          (.then (fn []
                   (let [node (js/AudioWorkletNode. ctx "nes-audio"
                                                    #js {:outputChannelCount #js [2]})
                         gain (.createGain ctx)]
                     (set! (.. gain -gain -value) volume)
                     (.connect node gain)
                     (.connect gain (.-destination ctx))
                     (reset! audio {:ctx ctx :node node :gain gain})
                     (.resume ctx))))))))

(defn disable!
  "Stop sound and release the audio device."
  []
  (when-let [{:keys [^js ctx]} @audio]
    (reset! audio nil)
    (.close ctx)))
