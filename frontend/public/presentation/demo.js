(() => {
  const stage = document.querySelector('.deck-stage');
  if (!stage) return;
  const slides = [...document.querySelectorAll('[data-demo-step]')];
  const slots = slides.map((slide) => slide.querySelector('[data-demo-frame-slot]'));
  const prefixInput = document.querySelector('[data-demo-prefix]');
  const introStatus = document.querySelector('[data-demo-intro-status]');
  const params = new URLSearchParams(location.search);
  const exporting = params.has('export');
  const live = params.has('live') && !exporting;
  document.body.classList.toggle('demo-live', live);
  let prefix = '';
  let started = false;
  let failed = false;
  let completed = 0;
  const startKey = 'campus-demo-last-start';
  let previousStart = -Infinity;
  try { previousStart = Number(sessionStorage.getItem(startKey)) || -Infinity; } catch { previousStart = -Infinity; }
  let run;
  let role;
  let studentName;
  let pending = '';
  let frameLeft = 0;
  let frameTop = 0;
  let frameScale = 1;
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const normalize = (value) => (value ?? '').replace(/\s+/g, ' ').trim();
  const matches = (value, expected) => expected instanceof RegExp ? expected.test(normalize(value)) : normalize(value) === expected;
  const setState = (slide, state, message = '') => {
    slide.dataset.demoState = state;
    slide.querySelector('[data-demo-status]').textContent = message;
  };

  slides.forEach((slide) => setState(slide, 'idle'));
  if (prefixInput) prefixInput.value = '';
  const clip = (step, kind) => `/presentation/demo/step-${step}.${kind}`;
  if (!live) {
    slides.forEach((slide, index) => {
      const step = Number(slide.dataset.demoStep);
      const slot = slots[index];
      slot.textContent = '';
      slot.classList.add('recorded');
      if (exporting) {
        const poster = document.createElement('img');
        poster.src = clip(step, 'end.jpg');
        poster.alt = '';
        slot.append(poster);
        return;
      }
      const video = document.createElement('video');
      video.src = clip(step, 'mp4');
      video.poster = clip(step, 'start.jpg');
      video.muted = true;
      video.playsInline = true;
      video.preload = 'auto';
      video.addEventListener('ended', () => setState(slide, 'done'));
      slot.append(video);
    });
    document.addEventListener('deck:next', (event) => {
      const slide = document.querySelector('section.slide.active');
      const video = slide?.querySelector('[data-demo-frame-slot] video');
      if (!video || slide.dataset.demoState !== 'idle') return;
      event.preventDefault();
      setState(slide, 'running');
      video.currentTime = 0;
      void video.play().catch(() => setState(slide, 'failed', 'Запись не воспроизводится'));
    });
    document.addEventListener('deck:slidechange', () => {
      slides.forEach((slide) => {
        if (slide.classList.contains('active')) return;
        const video = slide.querySelector('[data-demo-frame-slot] video');
        if (!video) return;
        video.pause();
        video.currentTime = 0;
        setState(slide, 'idle');
      });
    });
    return;
  }
  slots.forEach((slot) => { slot.textContent = 'campus-rating.ru'; });

  const frame = document.createElement('iframe');
  frame.title = 'Живое демо Campus Rating';
  Object.assign(frame.style, {
    position: 'absolute', width: '360px', height: '740px', boxSizing: 'content-box',
    border: '2px solid var(--demo-frame, #686868)', borderRadius: '28px',
    background: 'var(--demo-frame-bg, #ffffff)', transformOrigin: 'top left',
    visibility: 'hidden', zIndex: '10',
  });
  stage.append(frame);
  const cursor = document.createElement('div');
  cursor.className = 'demo-touch';
  cursor.setAttribute('aria-hidden', 'true');
  const finger = document.createElement('span');
  cursor.append(finger);
  stage.append(cursor);

  const deckKeys = ['ArrowRight', 'ArrowLeft', 'PageDown', 'PageUp', ' '];
  frame.addEventListener('load', () => {
    const hideScrollbars = frame.contentDocument?.createElement('style');
    if (hideScrollbars) {
      hideScrollbars.textContent = 'html, body, * { scrollbar-width: none; } ::-webkit-scrollbar { display: none; }';
      frame.contentDocument.head.append(hideScrollbars);
    }
    frame.contentDocument?.addEventListener('keydown', (event) => {
      const target = event.target;
      const win = frame.contentWindow;
      const typing = target instanceof win.HTMLInputElement || target instanceof win.HTMLTextAreaElement
        || target instanceof win.HTMLSelectElement || target?.isContentEditable;
      if (!deckKeys.includes(event.key) || typing) return;
      event.preventDefault();
      document.dispatchEvent(new KeyboardEvent('keydown', { key: event.key }));
    });
  });
  const returnFocus = () => {
    frame.contentDocument?.activeElement?.blur?.();
    window.focus();
  };
  const doc = () => frame.contentDocument;
  const visible = (element) => {
    if (!element || !element.getClientRects().length) return false;
    const style = element.ownerDocument.defaultView.getComputedStyle(element);
    return style.visibility !== 'hidden' && style.display !== 'none';
  };
  const name = (element) => {
    const ids = element.getAttribute('aria-labelledby');
    if (ids) return ids.split(/\s+/).map((id) => element.ownerDocument.getElementById(id)?.textContent ?? '').join(' ');
    return element.getAttribute('aria-label') ?? element.textContent;
  };
  const selectors = {
    button: 'button, [role="button"], input[type="submit"]', link: 'a[href], [role="link"]',
    heading: 'h1,h2,h3,h4,h5,h6,[role="heading"]', menuitem: '[role="menuitem"]',
    row: '[role="row"],tr', article: 'article,[role="article"]', option: '[role="option"]',
  };
  const byRole = (roleName, expected, root = doc()) => [...root.querySelectorAll(selectors[roleName] ?? `[role="${roleName}"]`)]
    .find((element) => visible(element) && matches(name(element), expected));
  const byText = (expected, root = doc()) => [...root.querySelectorAll('*')]
    .find((element) => visible(element) && matches(element.textContent, expected)
      && ![...element.children].some((child) => visible(child) && matches(child.textContent, expected)));
  const label = (expected) => [...doc().querySelectorAll('label')]
    .find((element) => matches(element.textContent?.replace(/\s*\*\s*$/, ''), expected));
  const control = (expected) => {
    const item = label(expected);
    return item?.control ?? (item?.htmlFor ? doc().getElementById(item.htmlFor) : item?.querySelector('input,textarea,select'));
  };
  async function wait(find, timeout = 15_000) {
    const end = performance.now() + timeout;
    while (performance.now() < end) {
      const result = find();
      if (result) return result;
      await sleep(75);
    }
    throw new Error('Стенд не ответил');
  }

  function pointOf(element) {
    const rect = element.getBoundingClientRect();
    return {
      x: frameLeft + (2 + rect.left + Math.min(rect.width / 2, 120)) * frameScale,
      y: frameTop + (2 + rect.top + Math.min(rect.height / 2, 28)) * frameScale,
    };
  }
  async function settle(element) {
    let last = element.getBoundingClientRect().top;
    for (let i = 0; i < 20; i++) {
      await sleep(80);
      const top = element.getBoundingClientRect().top;
      if (Math.abs(top - last) < 0.5) return;
      last = top;
    }
  }
  async function moveTo(element) {
    element.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
    await settle(element);
    const point = pointOf(element);
    if (!cursor.classList.contains('on')) {
      cursor.style.transition = 'none';
      cursor.style.transform = `translate(${point.x}px, ${point.y + 60}px)`;
      cursor.getBoundingClientRect();
      cursor.style.transition = '';
      cursor.classList.add('on');
    }
    cursor.style.transform = `translate(${point.x}px, ${point.y}px)`;
    await sleep(650);
  }
  function tap(element) {
    const point = pointOf(element);
    const mark = document.createElement('div');
    mark.className = 'demo-tap';
    mark.setAttribute('aria-hidden', 'true');
    mark.style.left = `${point.x}px`;
    mark.style.top = `${point.y}px`;
    stage.append(mark);
    finger.classList.remove('press');
    finger.getBoundingClientRect();
    finger.classList.add('press');
    setTimeout(() => mark.remove(), 900);
  }
  async function action(find, perform) {
    const element = await wait(() => {
      const found = find();
      return visible(found) && !found.disabled && found.getAttribute('aria-disabled') !== 'true' ? found : null;
    });
    await moveTo(element);
    tap(element);
    await sleep(160);
    await perform(element);
    await sleep(600);
    return element;
  }
  const click = (roleName, expected, root) => { pending = `${roleName} «${expected}»`; return action(() => byRole(roleName, expected, root), (element) => element.click()); };
  const text = (expected, root) => { pending = `текст «${expected}»`; return wait(() => byText(expected, root)); };
  const choose = (expected, root) => { pending = `выбор «${expected}»`; return action(() => byText(expected, root), (element) => element.click()); };
  function setValue(element, value) {
    const win = element.ownerDocument.defaultView;
    const prototype = element.tagName === 'TEXTAREA' ? win.HTMLTextAreaElement.prototype
      : element.tagName === 'SELECT' ? win.HTMLSelectElement.prototype : win.HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value').set.call(element, value);
    element.dispatchEvent(new win.Event('input', { bubbles: true }));
  }
  async function fill(expected, value) {
    pending = `поле «${expected}»`;
    await action(() => control(expected), async (element) => {
      element.focus();
      const typed = element.tagName !== 'SELECT' && !['datetime-local', 'date', 'time', 'month'].includes(element.type);
      if (typed) {
        for (let i = 1; i <= value.length; i++) {
          setValue(element, value.slice(0, i));
          await sleep(value.length > 30 ? 18 : 40);
        }
      } else setValue(element, value);
      element.dispatchEvent(new element.ownerDocument.defaultView.Event('change', { bubbles: true }));
    });
  }
  async function select(expected, value) {
    const element = await wait(() => control(expected));
    if (element.tagName === 'SELECT') await fill(expected, value);
    else {
      await action(() => control(expected), (item) => item.click());
      await click('option', value);
    }
  }
  async function navigate(path, timeout = 15_000) {
    pending = `переход ${path}`;
    cursor.classList.remove('on');
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { frame.removeEventListener('load', loaded); reject(new Error('Стенд не ответил')); }, timeout);
      function loaded() { clearTimeout(timer); resolve(); }
      frame.addEventListener('load', loaded, { once: true });
      frame.contentWindow.location.assign(path);
    });
    await sleep(900);
  }
  const path = () => frame.contentWindow.location.pathname;
  const waitPath = (pattern) => { pending = `адрес ${pattern}`; return wait(() => pattern.test(path())); };
  function renderRole() {
    slides.forEach((slide) => {
      slide.querySelector('[data-demo-role]').textContent = role === 'teacher' ? 'Преподаватель' : role === 'student' ? 'Студент' : '';
    });
  }
  async function signIn(target) {
    if (!started) {
      started = true;
      slots.forEach((slot) => { slot.textContent = ''; });
      position();
      await navigate('/login');
    }
    const surname = target === 'teacher' ? 'Петров' : 'Смирнова';
    if (role === target) {
      const current = await wait(() => byRole('button', /^Меню пользователя:/) || control('Email'));
      if (current.getAttribute('aria-label')?.includes(surname)) return;
    }
    const state = await wait(() => byRole('button', /^Меню пользователя:/) || control('Email'));
    if (state.tagName === 'BUTTON' || state.getAttribute('aria-label')?.startsWith('Меню пользователя:')) {
      await click('button', /^Меню пользователя:/);
      await click('menuitem', 'Выйти');
      await wait(() => control('Email'));
    }
    role = undefined;
    renderRole();
    await fill('Email', target === 'teacher' ? 'petrov@campus.test' : 'smirnova@student.campus.test');
    await fill('Пароль', `${prefix}-${target}-1`);
    await click('button', 'Войти');
    const menu = await wait(() => byRole('button', /^Меню пользователя:/));
    const fullName = menu.getAttribute('aria-label').replace('Меню пользователя: ', '');
    if (!fullName.includes(surname)) throw new Error('Неверная роль');
    role = target;
    renderRole();
    if (target === 'student') studentName = fullName;
  }
  async function tab(labelText, pattern) {
    pending = `меню «${labelText}»`;
    await click('link', labelText, await wait(() => doc().querySelector('nav[aria-label="Нижнее меню"]')));
    await waitPath(pattern);
    await sleep(500);
  }
  async function openTask() {
    await tab('Задачи', /^\/tasks$/);
    await click('link', run.checkpointTitle);
    await waitPath(/^\/tasks\/\d+$/);
  }
  async function openCheckpoint() {
    await tab('Точки', /^\/checkpoints$/);
    await click('link', run.checkpointTitle);
    await waitPath(/^\/checkpoints\/\d+$/);
  }
  const moscow = (instant) => new Date(instant + 3 * 3600_000).toISOString().slice(0, 16);
  async function submit() {
    await fill('Ссылка на работу', 'https://github.com/Daniiltyhtin/campus-rating');
    await click('button', 'Сдать работу');
    await text('Сдано на проверку');
  }
  const steps = {
    async 1() {
      await signIn('teacher');
      await tab('Точки', /^\/checkpoints$/);
      await click('link', 'Новая точка');
      await waitPath(/^\/checkpoints\/new$/);
      await fill('Название', run.checkpointTitle);
      await fill('Описание', 'Демонстрация на защите');
      await choose('ИВТ-21');
      await fill('Дедлайн (МСК)', moscow(run.deadline));
      await fill('Стоимость, АР', '12');
      await click('button', 'Создать точку');
      await waitPath(/^\/checkpoints\/\d+$/);
      await wait(() => byRole('heading', run.checkpointTitle));
    },
    async 2() {
      await signIn('teacher');
      await tab('Активности', /^\/activities$/);
      await click('link', 'Создать активность');
      await waitPath(/^\/activities\/new$/);
      await fill('Название', run.activityTitle);
      await fill('Описание', 'Демонстрация на защите');
      await select('Тип активности', 'SOCIAL');
      await choose(/^ИВТ-21 · /);
      await fill('Дата и время начала (Москва)', moscow(run.deadline));
      await fill('Максимум баллов', '75');
      await click('button', 'Создать активность');
      await waitPath(/^\/activities$/);
      await wait(() => byRole('heading', run.activityTitle));
    },
    async 3() {
      await signIn('student');
      await openTask();
      await text('В процессе');
      await tab('Активности', /^\/activities$/);
      await click('button', `Записаться: ${run.activityTitle}`);
      await text('Вы записались на активность');
    },
    async 4() {
      await signIn('student');
      await openTask();
      await wait(() => byText('Просрочено') && byText(/Списано (4|8|12) из 12 АР/));
    },
    async 5() {
      await signIn('student');
      await openTask();
      await submit();
      await signIn('teacher');
      await openCheckpoint();
      await click('button', `Действия: ${studentName}`);
      await click('menuitem', 'Вернуть…');
      await fill('Комментарий возврата', 'Добавьте ссылку на отчёт');
      await click('button', 'Вернуть работу');
      await wait(() => !byRole('button', 'Вернуть работу'));
      await signIn('student');
      await openTask();
      await text('Добавьте ссылку на отчёт');
      await submit();
      await signIn('teacher');
      await openCheckpoint();
      await action(() => label(studentName), (element) => element.click());
      await click('button', 'Принять 1 работу');
      await wait(() => {
        const row = [...doc().querySelectorAll('[role="row"]')].find((item) => item.textContent.includes(studentName));
        return row && byText('Принято', row);
      });
    },
    async 6() {
      await signIn('teacher');
      await tab('Активности', /^\/activities$/);
      await click('link', `Подтвердить участие: ${run.activityTitle}`);
      await waitPath(/^\/activities\/\d+\/confirm$/);
      const row = await wait(() => [...doc().querySelectorAll('[role="row"]')].find((item) => visible(item) && item.textContent.includes(studentName)));
      await choose('Выбрать', row);
      await choose('50 %', row);
      await click('button', 'Начислить 38 БОА');
      await text('Начислено 38 БОА');
    },
    async 7() {
      await signIn('student');
      await tab('Профиль', /^\/users\/\d+$/);
      await wait(() => [...doc().querySelectorAll('[aria-label="Балансы"]')].find(visible));
      await sleep(1500);
      await tab('Награды', /^\/awards$/);
      await wait(() => [...doc().querySelectorAll('article')].find((item) => visible(item)
        && item.textContent.includes(run.activityTitle) && /\+38 БОА/.test(item.textContent)));
    },
  };
  function position(slide = document.querySelector('section.slide.active')) {
    const slot = slide?.querySelector('[data-demo-frame-slot]');
    const shown = Boolean(slot) && started;
    frame.style.visibility = shown ? 'visible' : 'hidden';
    cursor.style.visibility = shown ? 'visible' : 'hidden';
    if (!slot) return;
    let left = 0;
    let top = 0;
    let current = slot;
    while (current && current !== stage) {
      left += current.offsetLeft;
      top += current.offsetTop;
      current = current.offsetParent;
    }
    frameLeft = left;
    frameTop = top;
    frameScale = Math.min(slot.offsetWidth / 364, slot.offsetHeight / 744);
    frame.style.left = `${left}px`;
    frame.style.top = `${top}px`;
    frame.style.transform = `scale(${frameScale})`;
  }
  const runnable = (step, slide) => {
    const state = slide.dataset.demoState;
    if (!prefix || state === 'done' || state === 'failed') return false;
    return step === 1 ? true : Boolean(run) && !failed && completed === step - 1;
  };
  const busy = () => slides.some((slide) => ['waiting', 'running'].includes(slide.dataset.demoState));
  async function start(step, slide) {
    if (step === 1) {
      const pause = previousStart + 60_000 - Date.now();
      if (pause > 0) {
        setState(slide, 'waiting', 'Ждём минуту после прошлого прогона…');
        await sleep(pause);
      }
      previousStart = Date.now();
      try { sessionStorage.setItem(startKey, String(previousStart)); } catch { previousStart = Date.now(); }
      started = false;
      role = undefined;
      const time = Math.floor(previousStart / 1000) * 1000;
      const hhmm = moscow(time).slice(11);
      run = { time, deadline: Math.ceil((time + 60_000) / 60_000) * 60_000, checkpointTitle: `Курсовая, этап ${hhmm}`, activityTitle: `Субботник ${hhmm}` };
      completed = 0;
      failed = false;
      slides.forEach((item) => { if (item !== slide) setState(item, 'idle'); });
    }
    if (step === 4) {
      const pause = run.deadline + 5_000 - Date.now();
      if (pause > 0) {
        setState(slide, 'waiting', 'Ждём дедлайн…');
        await sleep(pause);
      }
    }
    setState(slide, 'running');
    try {
      await steps[step]();
      completed = step;
      setState(slide, 'done');
    } catch (error) {
      console.error(`[demo] шаг ${step}: ${pending} — ${error?.message ?? error}`);
      failed = true;
      role = undefined;
      setState(slide, 'failed', 'Стенд не ответил');
      if (step !== 1) setState(slides[0], 'idle');
    } finally {
      cursor.classList.remove('on');
      returnFocus();
    }
  }
  prefixInput?.addEventListener('input', () => {
    prefix = prefixInput.value.trim();
    if (introStatus) introStatus.textContent = prefix ? 'Префикс введён' : 'Хранится только в этой вкладке до перезагрузки';
  });
  document.addEventListener('deck:next', (event) => {
    const slide = document.querySelector('section.slide.active');
    const step = Number(slide?.dataset.demoStep);
    if (!step) return;
    if (busy()) {
      event.preventDefault();
      return;
    }
    if (!runnable(step, slide)) return;
    event.preventDefault();
    void start(step, slide);
  });
  document.addEventListener('deck:slidechange', (event) => position(event.detail.slide));
  window.addEventListener('resize', () => position());
  position();
})();
