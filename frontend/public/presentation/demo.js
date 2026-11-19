(() => {
  const stage = document.querySelector('.deck-stage');
  if (!stage) return;
  const slides = [...document.querySelectorAll('[data-demo-step]')];
  const slots = slides.map((slide) => slide.querySelector('[data-demo-frame-slot]'));
  const prefixInput = document.querySelector('[data-demo-prefix]');
  const introStatus = document.querySelector('[data-demo-intro-status]');
  const exporting = new URLSearchParams(location.search).has('export');
  let prefix = '';
  let started = false;
  let running = false;
  let failed = false;
  let completed = 0;
  const startKey = 'campus-demo-last-start';
  let previousStart = -Infinity;
  try { previousStart = Number(sessionStorage.getItem(startKey)) || -Infinity; } catch { previousStart = -Infinity; }
  let run;
  let role;
  let studentName;
  let studentPath;
  let nextActionAt = 0;
  let pending = '';
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const normalize = (value) => (value ?? '').replace(/\s+/g, ' ').trim();
  const matches = (value, expected) => expected instanceof RegExp ? expected.test(normalize(value)) : normalize(value) === expected;

  slots.forEach((slot) => { slot.textContent = exporting ? 'Живое демо — campus-rating.ru' : 'Сайт откроется по кнопке шага'; });
  if (prefixInput) prefixInput.value = '';
  if (exporting) {
    slides.forEach((slide) => { slide.querySelector('[data-demo-run]').disabled = true; });
    return;
  }

  const frame = document.createElement('iframe');
  frame.title = 'Живое демо Campus Rating';
  Object.assign(frame.style, {
    position: 'absolute', width: '360px', height: '740px', boxSizing: 'content-box',
    border: '2px solid var(--demo-frame, #686868)', borderRadius: '28px',
    background: 'var(--demo-frame-bg, #ffffff)', transformOrigin: 'top left',
    visibility: 'hidden', zIndex: '10',
  });
  stage.append(frame);
  const deckKeys = ['ArrowRight', 'ArrowLeft', 'PageDown', 'PageUp'];
  frame.addEventListener('load', () => {
    frame.contentDocument?.addEventListener('keydown', (event) => {
      const target = event.target;
      const typing = target instanceof frame.contentWindow.HTMLInputElement || target instanceof frame.contentWindow.HTMLTextAreaElement
        || target instanceof frame.contentWindow.HTMLSelectElement || target?.isContentEditable;
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
  async function action(find, perform) {
    await sleep(Math.max(0, nextActionAt - performance.now()));
    const element = await wait(() => {
      const found = find();
      return visible(found) && !found.disabled && found.getAttribute('aria-disabled') !== 'true' ? found : null;
    });
    element.scrollIntoView({ block: 'center', inline: 'nearest' });
    perform(element);
    nextActionAt = performance.now() + 1500;
    return element;
  }
  const click = (roleName, expected, root) => { pending = `${roleName} «${expected}»`; return action(() => byRole(roleName, expected, root), (element) => element.click()); };
  const text = (expected, root) => { pending = `текст «${expected}»`; return wait(() => byText(expected, root)); };
  const choose = (expected, root) => { pending = `выбор «${expected}»`; return action(() => byText(expected, root), (element) => element.click()); };
  async function fill(expected, value) {
    pending = `поле «${expected}»`;
    await action(() => control(expected), (element) => {
      const win = element.ownerDocument.defaultView;
      const prototype = element.tagName === 'TEXTAREA' ? win.HTMLTextAreaElement.prototype
        : element.tagName === 'SELECT' ? win.HTMLSelectElement.prototype : win.HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(prototype, 'value').set.call(element, value);
      element.dispatchEvent(new win.Event('input', { bubbles: true }));
      element.dispatchEvent(new win.Event('change', { bubbles: true }));
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
    const end = performance.now() + timeout;
    await sleep(Math.max(0, nextActionAt - performance.now()));
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { frame.removeEventListener('load', loaded); reject(new Error('Стенд не ответил')); }, Math.max(0, end - performance.now()));
      function loaded() { clearTimeout(timer); resolve(); }
      frame.addEventListener('load', loaded, { once: true });
      frame.contentWindow.location.assign(path);
    });
    nextActionAt = performance.now() + 1500;
  }
  const path = () => frame.contentWindow.location.pathname;
  const waitPath = (pattern) => { pending = `адрес ${pattern}`; return wait(() => pattern.test(path())); };
  function renderRole() {
    slides.forEach((slide) => {
      slide.querySelector('[data-demo-role]').textContent = role === 'teacher' ? 'Преподаватель Петров' : role === 'student' ? 'Студент Смирнова' : '';
    });
  }
  async function signIn(target) {
    if (!started) {
      started = true;
      slots.forEach((slot) => { slot.textContent = ''; });
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
  const moscow = (instant) => new Date(instant + 3 * 3600_000).toISOString().slice(0, 16);
  async function submit() {
    await fill('Ссылка на работу', 'https://github.com/Daniiltyhtin/campus-rating');
    await click('button', 'Сдать работу');
    await text('Сдано на проверку');
  }
  const steps = {
    async 1() {
      await signIn('teacher');
      await navigate('/checkpoints/new');
      await fill('Название', run.checkpointTitle);
      await fill('Описание', 'Демонстрация на защите');
      await choose('ИВТ-21');
      await fill('Дедлайн (МСК)', moscow(run.deadline));
      await fill('Стоимость, АР', '12');
      await click('button', 'Создать точку');
      await waitPath(/^\/checkpoints\/\d+$/);
      await wait(() => byRole('heading', run.checkpointTitle));
      run.checkpointPath = path();
    },
    async 2() {
      await signIn('teacher');
      await navigate('/activities/new');
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
      await navigate('/tasks');
      await click('link', run.checkpointTitle);
      await waitPath(/^\/tasks\/\d+$/);
      run.taskPath = path();
      await text('В процессе');
      await navigate('/activities');
      await click('button', `Записаться: ${run.activityTitle}`);
      await text('Вы записались на активность');
    },
    async 4() {
      await signIn('student');
      const end = performance.now() + 14_500;
      await navigate(run.taskPath, 14_500);
      await wait(() => byText('Просрочено') && byText(/Списано (4|8|12) из 12 АР/), Math.max(1, end - performance.now()));
    },
    async 5() {
      await signIn('student');
      await navigate(run.taskPath);
      await submit();
      await signIn('teacher');
      await navigate(run.checkpointPath);
      await click('button', `Действия: ${studentName}`);
      await click('menuitem', 'Вернуть…');
      await fill('Комментарий возврата', 'Добавьте ссылку на отчёт');
      await click('button', 'Вернуть работу');
      await wait(() => !byRole('button', 'Вернуть работу'));
      await signIn('student');
      await navigate(run.taskPath);
      await text('Добавьте ссылку на отчёт');
      await submit();
      await signIn('teacher');
      await navigate(run.checkpointPath);
      await action(() => label(studentName), (element) => element.click());
      await click('button', 'Принять 1 работу');
      await wait(() => {
        const row = [...doc().querySelectorAll('[role="row"]')].find((item) => item.textContent.includes(studentName));
        return row && byText('Принято', row);
      });
    },
    async 6() {
      await signIn('teacher');
      await navigate('/activities');
      const link = await wait(() => byRole('link', `Подтвердить участие: ${run.activityTitle}`));
      const confirmPath = link.getAttribute('href');
      await navigate(confirmPath);
      const row = await wait(() => [...doc().querySelectorAll('[role="row"]')].find((item) => visible(item) && item.textContent.includes(studentName)));
      studentPath = byRole('link', studentName, row).getAttribute('href');
      await choose('Выбрать', row);
      await choose('50 %', row);
      await click('button', 'Начислить 38 БОА');
      await text('Начислено 38 БОА');
    },
    async 7() {
      await signIn('student');
      await navigate(studentPath);
      await wait(() => [...doc().querySelectorAll('[aria-label="Балансы"]')].find(visible));
      await sleep(1500);
      await navigate('/awards');
      await wait(() => [...doc().querySelectorAll('article')].find((item) => visible(item)
        && item.textContent.includes(run.activityTitle) && /\+38 БОА/.test(item.textContent)));
    },
  };
  function refresh() {
    slides.forEach((slide) => {
      const step = Number(slide.dataset.demoStep);
      const button = slide.querySelector('[data-demo-run]');
      const remaining = Math.max(0, Math.ceil(((step === 1 ? previousStart + 60_000 : step === 4 && run ? run.deadline + 5_000 : 0) - Date.now()) / 1000));
      button.disabled = !prefix || running || remaining > 0 || (step > 1 && (failed || completed !== step - 1));
      const caption = remaining > 0 ? `Запустить шаг (${remaining} с)` : 'Запустить шаг';
      if (button.textContent !== caption) button.textContent = caption;
    });
  }
  function position(slide = document.querySelector('section.slide.active')) {
    const slot = slide?.querySelector('[data-demo-frame-slot]');
    frame.style.visibility = slot ? 'visible' : 'hidden';
    if (!slot) return;
    let left = 0;
    let top = 0;
    let current = slot;
    while (current && current !== stage) {
      left += current.offsetLeft;
      top += current.offsetTop;
      current = current.offsetParent;
    }
    frame.style.left = `${left}px`;
    frame.style.top = `${top}px`;
    frame.style.transform = `scale(${Math.min(slot.offsetWidth / 364, slot.offsetHeight / 744)})`;
    frame.style.visibility = started ? 'visible' : 'hidden';
  }
  prefixInput?.addEventListener('input', () => {
    prefix = prefixInput.value.trim();
    if (introStatus) introStatus.textContent = prefix ? 'Префикс введён' : 'Введите префикс для запуска демо';
    refresh();
  });
  slides.forEach((slide) => {
    slide.querySelector('[data-demo-run]').addEventListener('click', async () => {
      const button = slide.querySelector('[data-demo-run]');
      if (button.disabled) return;
      const step = Number(slide.dataset.demoStep);
      if (step === 1) {
        previousStart = Date.now();
        try { sessionStorage.setItem(startKey, String(previousStart)); } catch { previousStart = Date.now(); }
        started = false;
        role = undefined;
        const time = Math.floor(previousStart / 1000) * 1000;
        const hhmm = moscow(time).slice(11);
        run = { time, deadline: Math.ceil((time + 60_000) / 60_000) * 60_000, checkpointTitle: `Курсовая, этап ${hhmm}`, activityTitle: `Субботник ${hhmm}` };
        completed = 0;
        failed = false;
        slides.forEach((item) => { item.querySelector('[data-demo-status]').textContent = ''; });
      }
      running = true;
      refresh();
      const status = slide.querySelector('[data-demo-status]');
      status.textContent = 'Выполняется…';
      try {
        const promise = steps[step]();
        position();
        await promise;
        completed = step;
        status.textContent = 'Шаг выполнен';
      } catch (error) {
        console.error(`[demo] шаг ${step}: ${pending} — ${error?.message ?? error}`);
        failed = true;
        role = undefined;
        status.textContent = 'Стенд не ответил';
      } finally {
        running = false;
        refresh();
        position();
        returnFocus();
      }
    });
  });
  document.addEventListener('deck:slidechange', (event) => position(event.detail.slide));
  window.addEventListener('resize', () => position());
  setInterval(refresh, 250);
  refresh();
  position();
})();
