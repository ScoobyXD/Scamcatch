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
  const UNEXPECTED = 'Voice sent something unexpected and stopped.';
  const MIC_ERRORS = {
    NotAllowedError: 'Microphone blocked. Allow it in your browser to talk.',
    SecurityError: 'Microphone blocked. Allow it in your browser to talk.',
    NotFoundError: 'No microphone found.',
    NotReadableError: 'Another app is using the microphone.',
  };

  let current = null;
  const problem = (message) => Object.assign(new Error(message), { friendly: true });

  function start(context, { onStatus = () => {}, onCaption = () => {} } = {}) {
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
    let voiceStarted = false;
    let captionText = '';
    let captionEnd = NaN;
    let userSpoke = true;
    const audio = new Audio();
    audio.autoplay = true;
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
          if (!voiceStarted) {
            voiceStarted = true;
            clearTimeout(micTimer);
            micTimer = setTimeout(openMic, MIC_GRACE_MS);
          }
          caption(event);
          break;
        case 'error':
          stop('The voice hit a problem and stopped. Try again.');
          break;
      }
    }

    async function connect() {
      if (!navigator.mediaDevices?.getUserMedia || !window.RTCPeerConnection) {
        throw problem('This browser can’t run live voice. Try a recent Chrome, Edge, or Safari.');
      }
      if (!(await microphoneAllowed())) say('Allow the microphone to talk');
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
      } catch (error) {
        throw problem(MIC_ERRORS[error?.name] || 'The microphone couldn’t start.');
      }
      if (stopped) {
        release();
        return;
      }
      // A disabled track still sends audio (silence), which keeps the input flowing as OpenAI requires.
      stream.getAudioTracks().forEach((track) => { track.enabled = false; });
      say('Connecting…');
      startTimer = setTimeout(() => stop('The voice took too long to connect. Try again.'), START_TIMEOUT_MS);
      peer = new RTCPeerConnection();
      peer.ontrack = (event) => {
        audio.srcObject = event.streams[0] || new MediaStream([event.track]);
        audio.play().catch(() => {});
      };
      peer.onconnectionstatechange = watchConnection;
      peer.addTrack(stream.getAudioTracks()[0], stream);
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
      prompts = await post({ sdp: peer.localDescription.sdp, context, ...(voice ? { voice } : {}) });
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
