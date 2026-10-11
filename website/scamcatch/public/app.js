/* ScamCatch homepage: demo timeline, call screen, section index. */
(() => {
  const card = document.querySelector('.card-demo');
  if (!card) return;

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const PHASES = ['trust', 'ask', 'warn', 'voice'];
  const typing = card.querySelector('.typing');
  const stepped = [...card.querySelectorAll('.msg[data-step]')];
  const flagged = card.querySelector('.msg.flagged');
  const storyLines = [...card.querySelectorAll('.story-line')];
  const trackerItems = [...card.querySelectorAll('.tracker li')];
  const callStatus = card.querySelector('.call-status');
  const caption = card.querySelector('.caption');
  const phone = card.querySelector('.phone');

  const LOOP_HOLD = 16000;   // how long the "tap here" screen waits before replaying
  let stepIndex = 0;
  let timer = null;
  let paused = false;
  let calling = false;
  let lastInteraction = 0;
  let liveSession = null;

  const show = (el) => el.classList.add('is-shown');
  const hide = (el) => el.classList.remove('is-shown');

  function setPhase(phase, durationMs) {
    card.dataset.phase = phase;
    const current = PHASES.indexOf(phase);
    storyLines.forEach((line) => line.classList.toggle('is-active', line.dataset.for === phase));
    trackerItems.forEach((item) => {
      const index = PHASES.indexOf(item.dataset.for);
      item.classList.remove('is-active');
      item.classList.toggle('is-done', index < current || (index === current && !durationMs));
      if (index === current && durationMs) {
        item.style.setProperty('--dur', `${durationMs}ms`);
        void item.offsetWidth; // restart the fill
        item.classList.add('is-active');
      }
    });
  }

  function reset() {
    stepped.forEach(hide);
    hide(typing);
    flagged.classList.remove('is-scanned');
    card.classList.remove('has-watch', 'has-flag', 'has-ready', 'is-calling');
    callStatus.textContent = '';
    caption.textContent = 'Say “hey” to start.';
  }

  // [wait before this step in ms, what happens]
  const script = [
    [0, () => { reset(); setPhase('trust', 4700); }],
    [500, () => card.classList.add('has-watch')],
    [400, () => show(typing)],
    [1300, () => { hide(typing); show(stepped[0]); }],
    [1000, () => { show(typing); setPhase('ask', 4400); }],
    [1800, () => { hide(typing); show(stepped[1]); }],
    [1400, () => { flagged.classList.add('is-scanned'); card.classList.add('has-flag'); }],
    [1200, () => {
      setPhase('warn', 2800);
      phone.classList.remove('buzz');
      void phone.offsetWidth;
      phone.classList.add('buzz');
    }],
    [2800, () => { setPhase('voice'); card.classList.add('has-ready'); }],
    [LOOP_HOLD, () => {
      const idle = Date.now() - lastInteraction;
      if (calling) return;
      if (idle < 6000) { schedule(stepIndex - 1, 6000); return; }
      play(0);
    }],
  ];

  function schedule(index, wait) {
    clearTimeout(timer);
    stepIndex = index;
    timer = setTimeout(() => {
      if (paused) return;
      const step = script[stepIndex];
      stepIndex += 1;
      step[1]();
      if (stepIndex < script.length) schedule(stepIndex, script[stepIndex][0]);
    }, wait);
  }

  function play(from = 0) {
    clearTimeout(timer);
    if (calling) endCall();
    schedule(from, script[from][0]);
  }

  function pause() { paused = true; clearTimeout(timer); }
  function resume() {
    if (!paused) return;
    paused = false;
    if (!calling && stepIndex < script.length) schedule(stepIndex, script[stepIndex][0]);
  }

  // Jump straight to the "tap here to talk" screen.
  function showFinalState() {
    clearTimeout(timer);
    reset();
    stepped.forEach(show);
    flagged.classList.add('is-scanned');
    card.classList.add('has-watch', 'has-flag', 'has-ready');
    setPhase('voice');
    stepIndex = script.length - 1;
  }

  /* ---- The conversation the live voice starts from ---- */
  function demoContext() {
    const messages = [...card.querySelectorAll('.thread .msg')]
      .filter((m) => !m.dataset.step || m.classList.contains('is-shown') || calling)
      .map((m) => ({
        from: m.classList.contains('me') ? 'user' : 'contact',
        name: m.classList.contains('me') ? 'You' : 'Daniel',
        text: m.textContent.trim(),
      }));
    return {
      scenario: 'romance_crypto_investment',
      app: 'chat',
      contactName: 'Daniel',
      flaggedMessage: flagged.textContent.trim(),
      conversation: messages,
    };
  }

  function startCall() {
    if (calling) return;
    if (card.dataset.phase !== 'voice') showFinalState();
    clearTimeout(timer);
    calling = true;
    card.classList.add('is-calling');
    callStatus.textContent = 'Connecting…';
    caption.textContent = 'Say “hey” to start.';

    const context = demoContext();
    const live = window.ScamCatchLive;
    if (live && typeof live.start === 'function') {
      // Live voice hooks in here: ScamCatchLive.start(context, { onStatus, onCaption }) → session with end()
      liveSession = live.start(context, {
        onStatus: (text) => { callStatus.textContent = text; },
        onCaption: (text) => { caption.textContent = text; },
      });
    } else {
      setTimeout(() => {
        if (!calling) return;
        callStatus.textContent = 'Live voice isn’t connected in this build yet';
        caption.textContent = 'The voice will start from the chat you just watched.';
      }, 1400);
    }
  }

  function endCall() {
    if (!calling) return;
    calling = false;
    if (liveSession && typeof liveSession.end === 'function') liveSession.end();
    liveSession = null;
    card.classList.remove('is-calling');
    callStatus.textContent = '';
    lastInteraction = Date.now();
    if (!reduceMotion) schedule(script.length - 1, LOOP_HOLD);
  }

  /* ---- Controls ---- */
  document.addEventListener('click', (event) => {
    const action = event.target.closest('[data-action]');
    if (!action) return;
    const kind = action.dataset.action;
    if (kind === 'talk') startCall();
    if (kind === 'end') endCall();
    if (kind === 'replay') { lastInteraction = 0; reduceMotion ? showFinalState() : play(0); }
    if (kind === 'restart') {
      event.preventDefault();
      document.getElementById('demo').scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'center' });
      reduceMotion ? showFinalState() : play(0);
    }
  });
  ['pointermove', 'focusin'].forEach((type) => phone.addEventListener(type, () => { lastInteraction = Date.now(); }));

  /* ---- Section index + pause the demo when it's off screen ---- */
  const links = new Map([...document.querySelectorAll('.index a')].map((a) => [a.hash.slice(1), a]));
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.target.id === 'demo') entry.isIntersecting ? resume() : pause();
      if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
        links.forEach((a, id) => a.setAttribute('aria-current', id === entry.target.id ? 'true' : 'false'));
      }
    });
  }, { threshold: [0, 0.5] });
  document.querySelectorAll('.stage').forEach((stage) => observer.observe(stage));
  document.addEventListener('visibilitychange', () => (document.hidden ? pause() : resume()));

  window.ScamCatchDemo = { context: demoContext, startCall, endCall, replay: () => play(0) };

  if (reduceMotion) showFinalState();
  else play(0);
})();
