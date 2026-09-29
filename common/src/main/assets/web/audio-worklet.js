import { createMicResampler } from './browser-audio.js';

class WheelPlayMicrophone extends AudioWorkletProcessor {
  constructor() {
    super(); this.capture = null;
    this.port.onmessage = ({ data }) => {
      this.capture = data ? createMicResampler(sampleRate, data.rate, data.channels,
        packet => this.port.postMessage(packet, [packet])) : null;
    };
  }
  process(inputs) {
    if (this.capture && inputs[0]?.[0]) this.capture(inputs[0][0]);
    // Output stays silent: captured microphone audio is never monitored locally.
    return true;
  }
}
registerProcessor('wheelplay-microphone', WheelPlayMicrophone);
