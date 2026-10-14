/* ScamCatch live voice: browser WebRTC client for POST /api/voice/session (website/scamcatch/server.py locally). */
(() => {
  const ENDPOINT = '/api/voice/session';
  const CALL_LIMIT_MS = 3 * 60 * 1000;  // hard cap per call: every session is billed
  const START_TIMEOUT_MS = 35000;       // these four match LiveVoice
  const ICE_TIMEOUT_MS = 10000;
  const DISCONNECT_MS = 5000;
  const CLOSE_TIMEOUT_MS = 5000;
  const SPEAK_FIRST_MS = 6000;          // the voice speaks first; open the mic anyway if it hasn't started by then
  const MIC_GRACE_MS = 1000;            // after the voice starts talking, wait this long before listening
  const MAX_EVENT_CHARS = 128 * 1024;
  const CAPTION_CHARS = 90;
  const CAPTION_PAUSE_MS = 1200;
  const TURN_SILENCE_MS = 1200;         // the voice has been quiet this long after talking: it's the user's turn
  const LISTEN_ONLY_END_MS = 3000;      // without a microphone, the call ends once the voice has been quiet this long
  const VOICE_LEVEL = 0.01;             // remote audio above this RMS counts as the voice talking
  const LEVEL_MS = 100;                 // how often the call screen reads audio levels
  const UNEXPECTED = 'Voice sent something unexpected and stopped.';
  const MIC_ERRORS = {
    NotAllowedError: 'Microphone blocked. Allow it in your browser to talk.',
    SecurityError: 'Microphone blocked. Allow it in your browser to talk.',
    NotFoundError: 'No microphone found.',
    NotReadableError: 'Another app is using the microphone.',
  };

  let current = null;
  const problem = (message) => Object.assign(new Error(message), { friendly: true });

  function start(context, { onStatus = () => {}, onCaption = () => {}, onTurn = () => {}, onMicLevel = () => {} } = {}) {
    if (current) current.end();
    let stopped = false;   // no more statuses or commands after this
    let started = false;   // session.started arrived
    let stream = null;
    let peer = null;
    let channel = null;
    let request = null;
    let prompts = null;
    let startTimer = null;
    let disconnectTimer = null;
    let closeTimer = null;
    let micTimer = null;
    let micOpen = false;
    let micProblem = '';   // set when the microphone couldn't open: the visitor can listen but not talk
    let voiceStarted = false;
    let captionText = '';
    let captionEnd = NaN;
    let userSpoke = true;
    let lastWordsAt = 0;   // when the last output transcript delta arrived
    let lastVoiceAt = 0;   // when the voice's audio was last above VOICE_LEVEL
    let turn = '';
    let voiceMeter = null;
    let micMeter = null;
    // Created during the tap, so the browser lets it run. It only measures levels for the call screen; nothing plays through it.
    const meters = window.AudioContext ? new AudioContext() : null;
    // Silence, sent in place of a microphone the visitor didn't allow, so the input keeps flowing.
    const silence = meters ? meters.createMediaStreamDestination().stream : null;
    const levelTimer = setInterval(watchLevels, LEVEL_MS);
    const audio = new Audio();
    audio.autoplay = true;
    // Started during the tap, so Safari lets the voice play later even when no microphone is capturing.
    audio.srcObject = silence;
    audio.play().catch(() => {});
    const limitTimer = setTimeout(() => stop('Time’s up. Calls end after 3 minutes.'), CALL_LIMIT_MS);
    const say = (text) => { if (!stopped) onStatus(text); };

    // Ends the call. A started session gets session.close and up to five seconds to confirm with session.closed.
    function stop(text) {
      if (stopped) return;
      if (text) onStatus(text);
      stopped = true;
      clearTimeout(limitTimer);
      clearTimeout(startTimer);
      clearTimeout(disconnectTimer);
      clearTimeout(micTimer);
      clearInterval(levelTimer);
      if (meters) meters.close().catch(() => {});
      if (stream) stream.getTracks().forEach((track) => track.stop());
      audio.muted = true;
      audio.pause();
      if (request) request.abort();
      if (started && send({ type: 'session.close' })) closeTimer = setTimeout(release, CLOSE_TIMEOUT_MS);
      else release();
    }

    function release() {
      clearTimeout(closeTimer);
      if (stream) stream.getTracks().forEach((track) => track.stop());
      audio.srcObject = null;
      if (channel) channel.close();
      if (peer) peer.close();
      if (current === session) current = null;
    }

    function send(event) {
      if (!channel || channel.readyState !== 'open' || channel.bufferedAmount >= MAX_EVENT_CHARS) return false;
      channel.send(JSON.stringify(event));
      return true;
    }

    // The voice always speaks first: the mic sends silence until the voice starts talking.
    function openMic() {
      if (micOpen || stopped) return;
      micOpen = true;
      clearTimeout(micTimer);
      if (stream) stream.getAudioTracks().forEach((track) => { track.enabled = true; });
    }

    // An analyser on a media stream, for its level. Null when the browser can't measure it.
    function meter(mediaStream) {
      if (!meters) return null;
      try {
        const node = meters.createAnalyser();
        node.fftSize = 512;
        meters.createMediaStreamSource(mediaStream).connect(node);
        return { node, data: new Float32Array(node.fftSize) };
      } catch {
        return null;
      }
    }
    function level(m) {
      m.node.getFloatTimeDomainData(m.data);
      let sum = 0;
      for (const value of m.data) sum += value * value;
      return Math.sqrt(sum / m.data.length);
    }

    // Whose turn it is: the voice's, while its audio is playing; the user's, once it has gone quiet after talking.
    // Transcript deltas arrive ahead of the audio, so the audio level decides; without it, a pause in the transcript does.
    function watchLevels() {
      if (stopped) return;
      const now = performance.now();
      if (voiceMeter && level(voiceMeter) > VOICE_LEVEL) lastVoiceAt = now;
      if (micMeter && micOpen) onMicLevel(Math.min(1, level(micMeter) * 8));
      if (!voiceStarted) return;
      const quietFor = (ms) => (lastVoiceAt ? now - lastVoiceAt > ms : now - lastWordsAt > ms * 2) && now - lastWordsAt > ms / 2;
      // Listen only: nobody can talk back, so the call ends once the voice's opening is done.
      if (micProblem) {
        if (quietFor(LISTEN_ONLY_END_MS)) stop(micProblem);
        return;
      }
      const next = quietFor(TURN_SILENCE_MS) ? 'you' : 'voice';
      if (next !== turn) {
        turn = next;
        onTurn(next);
      }
    }

    function command(event) {
      if (!send(event)) stop('The voice connection stopped.');
    }

    async function microphoneAllowed() {
      try {
        return (await navigator.permissions.query({ name: 'microphone' })).state === 'granted';
      } catch {
        return false;
      }
    }

    function iceGathered() {
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(problem('Voice couldn’t set up a network connection. Try again.')), ICE_TIMEOUT_MS);
        const check = () => {
          if (peer.iceGatheringState !== 'complete') return;
          clearTimeout(timer);
          resolve();
        };
        peer.addEventListener('icegatheringstatechange', check);
        check();
      });
    }

    async function post(body) {
      request = new AbortController();
      let response;
      try {
        response = await fetch(ENDPOINT, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
          cache: 'no-store',
          signal: request.signal,
        });
      } catch {
        throw problem('Live voice isn’t available here right now.');
      }
      const result = await response.json().catch(() => null);
      if (!response.ok) throw problem(typeof result?.error === 'string' ? result.error : 'Live voice isn’t available on this site yet.');
      if (typeof result?.sdp !== 'string' || typeof result.greeting !== 'string' || typeof result.noBackendCommentary !== 'string') {
        throw problem('Voice returned an unreadable connection.');
      }
      return result;
    }

    function watchConnection() {
      clearTimeout(disconnectTimer);
      if (peer.connectionState === 'disconnected') disconnectTimer = setTimeout(() => stop('The voice connection was lost.'), DISCONNECT_MS);
      if (peer.connectionState === 'failed') stop('The voice connection was lost.');
    }

    // Transcript deltas mark no turn ends: start a new caption after the user speaks or after a pause in the voice.
    function caption(event) {
      if (typeof event.delta !== 'string') return;
      if (userSpoke || event.start_ms - captionEnd > CAPTION_PAUSE_MS) captionText = '';
      userSpoke = false;
      captionEnd = Number(event.end_ms);
      captionText = (captionText + event.delta).slice(-1000);
      const text = captionText.trim();
      const tail = text.slice(-CAPTION_CHARS);
      onCaption(text.length <= CAPTION_CHARS ? text : `…${tail.slice(tail.indexOf(' ') + 1)}`);
    }

    function receive(data) {
      let event = null;
      try {
        if (typeof data === 'string' && data.length <= MAX_EVENT_CHARS) event = JSON.parse(data);
      } catch { /* handled below */ }
      if (!event || typeof event.type !== 'string') {
        stop(UNEXPECTED);
        return;
      }
      if (event.type === 'session.closed') {
        started = false;
        stop('The call ended.');
        release();
        return;
      }
      if (stopped) return;
      switch (event.type) {
        case 'session.started':
          if (started) return;
          started = true;
          clearTimeout(startTimer);
          command({ type: 'session.instructions.append', event_id: 'scamcatch_greeting', delegation_id: null, content: prompts.greeting });
          if (!voiceStarted) micTimer = setTimeout(openMic, SPEAK_FIRST_MS);
          say('Connected');
          break;
        case 'session.delegation.created':
          if (typeof event.delegation?.id === 'string' && event.delegation.id) {
            command({ type: 'session.commentary.append', delegation_id: event.delegation.id, content: prompts.noBackendCommentary });
          }
          break;
        case 'session.input_transcript.delta':
          userSpoke = true;
          break;
        case 'session.output_transcript.delta':
          lastWordsAt = performance.now();
          if (!voiceStarted) {
            voiceStarted = true;
            clearTimeout(micTimer);
            micTimer = setTimeout(openMic, MIC_GRACE_MS);
            if (micProblem) say('ScamCatch is talking. Your mic is off.');
          }
          caption(event);
          break;
        case 'error':
          stop('The voice hit a problem and stopped. Try again.');
          break;
      }
    }

    async function connect() {
      if (!window.RTCPeerConnection) {
        throw problem('This browser can’t run live voice. Try a recent Chrome, Edge, or Safari.');
      }
      // An already allowed microphone opens while the call connects. Otherwise ask first, so the call knows
      // before it starts whether the visitor can talk. Without a microphone, the visitor still hears the voice's opening.
      const allowed = await microphoneAllowed();
      if (!allowed) say('Allow the microphone to talk');
      const asked = navigator.mediaDevices?.getUserMedia
        ? navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } })
        : Promise.reject(new Error('no getUserMedia'));
      const microphone = asked
        .then((opened) => {
          if (stopped) {   // the call ended while the microphone was opening
            opened.getTracks().forEach((track) => track.stop());
            return null;
          }
          stream = opened;
          // A disabled track still sends audio (silence), which keeps the input flowing as OpenAI requires.
          stream.getAudioTracks().forEach((track) => { track.enabled = false; });
          micMeter = meter(stream);
          return stream.getAudioTracks()[0];
        }, (error) => {
          micProblem = MIC_ERRORS[error?.name] || 'The microphone couldn’t start.';
          return silence ? silence.getAudioTracks()[0] : null;
        });
      if (!allowed) await microphone;
      if (stopped) {
        release();
        return;
      }
      say(micProblem ? 'Connecting… Your mic is off, so you can listen.' : 'Connecting…');
      startTimer = setTimeout(() => stop('The voice took too long to connect. Try again.'), START_TIMEOUT_MS);
      peer = new RTCPeerConnection();
      peer.ontrack = (event) => {
        audio.srcObject = event.streams[0] || new MediaStream([event.track]);
        audio.play().catch(() => {});
        voiceMeter = meter(audio.srcObject);
      };
      peer.onconnectionstatechange = watchConnection;
      // The microphone joins this sender as soon as it opens.
      const sender = peer.addTransceiver('audio', { direction: 'sendrecv' }).sender;
      // The event channel must exist before the offer.
      channel = peer.createDataChannel('oai-events');
      channel.onmessage = (event) => receive(event.data);
      channel.onclose = () => {
        stop('The voice connection ended.');
        release();
      };
      await peer.setLocalDescription(await peer.createOffer());
      await iceGathered();
      if (stopped) return;
      const voice = new URLSearchParams(location.search).get('voice');
      const answer = post({ sdp: peer.localDescription.sdp, context, ...(voice ? { voice } : {}) });
      answer.catch(() => {});   // awaited below; this keeps a call that ends first from leaving it unhandled
      const track = await microphone;
      if (stopped) {
        release();
        return;
      }
      await sender.replaceTrack(track);
      prompts = await answer;
      if (stopped) return;
      await peer.setRemoteDescription({ type: 'answer', sdp: prompts.sdp });
    }

    const session = {
      end: () => stop(),
      leave: () => stop('The call ended when you left the page.'),
    };
    current = session;
    connect().catch((error) => stop(error?.friendly ? error.message : 'Voice couldn’t connect. Try again.'));
    return session;
  }

  // Leaving or hiding the page ends the call, like backgrounding the app.
  const leave = () => { if (current) current.leave(); };
  window.addEventListener('pagehide', leave);
  document.addEventListener('visibilitychange', () => { if (document.hidden) leave(); });

  window.ScamCatchLive = { start };
})();
