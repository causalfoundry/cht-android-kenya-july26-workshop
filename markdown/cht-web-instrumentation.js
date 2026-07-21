/* CHT CF SDK Web Instrumentation; bridge-contract=1 */
/**
 * CHT DEVELOPER HANDOFF
 *
 * This is the only file CHT web developers edit and publish to the configured CDN.
 * It contains all updateable CHT event detection, classification, metadata, filtering,
 * form timing, PouchDB hooks, sync events, and user/facility resolution.
 *
 * Release rules:
 *   1. Increment VERSION for every publication, including rollbacks.
 *   2. Keep this first-line marker, BRIDGE_CONTRACT, BRIDGE_NAME, and the final export.
 *   3. Keep the UTF-8 file below 256 KiB and run: node --check <this-file>.
 *   4. Never send cookies, form answers, document bodies, remote URLs, or raw errors.
 *
 * Event groups currently emitted: client, client_form, task, report, and sync.
 * Sync emits start, complete, error, and denied; it intentionally has no up_to_date event.
 * Login/catalog compatibility is split with Android: this file resolves the CHT identity
 * and calls logUser; Android performs the native CF Identify/Login and catalog calls.
 */
(function () {
  if (window.top !== window) {
    return;
  }

  const VERSION = 29;
  const BRIDGE_CONTRACT = 1;
  const BRIDGE_NAME = 'cf_sdk_android';
  const DEFAULT_COUNTRY = 'Kenya';
  const EVENT_NAMES = {
    CLIENT: 'client',
    CLIENT_FORM: 'client_form',
    TASK: 'task',
    REPORT: 'report',
    SYNC: 'sync'
  };
  const CLIENT_CONTACT_TYPES = ['person', 'f_client'];
  const HOUSEHOLD_CONTACT_TYPES = ['e_household'];
  const PLACE_CONTACT_TYPES = [
    'clinic', 'health_center', 'district_hospital', 'place',
    'd_community_health_volunteer_area'
  ];
  const IGNORED_DOCUMENT_TYPES = [
    'telemetry', 'feedback', 'user-settings', 'meta', 'form',
    'translations', 'info', 'task', 'target'
  ];
  if (window.__cfSdkChtInstrumentation && window.__cfSdkChtInstrumentation.version === VERSION) {
    return;
  }

  // The screen the user is currently looking at. Kept only to compute durations and
  // to route doc writes to the right event; no screen events are emitted.
  let screen = null;

  // The most recently opened task (#/tasks/<emissionId>), used to attach task_id and
  // duration to the report a task completion produces.
  let lastTask = null;

  // The CHW's site (user-settings facility_id) for site_id; userResolved flips once we
  // have identified the user (by uuid) and logged the catalog this session.
  let facilityId = null;
  let userResolved = false;

  // Structural contact index used only to validate ids and parent relationships.
  // It stores no names, phone numbers, form answers, or document bodies.
  const contactIndex = new Map();
  let contactIndexPending = false;
  let contactIndexWarmed = false;

  function now() {
    return Date.now();
  }

  function str(value) {
    if (value === undefined || value === null || value === '') {
      return null;
    }
    return String(value).slice(0, 120);
  }

  // Full Couch task document ids are longer than the ordinary metadata cap.
  function longId(value) {
    if (value === undefined || value === null || value === '') {
      return null;
    }
    return String(value).slice(0, 300);
  }

  // Replication errors can contain request URLs, document ids, or server text. Keep only
  // bounded structural fields that are safe to send to analytics.
  function errorMeta(err) {
    const rawCode = err && (err.name || err.error);
    const code = rawCode
      ? String(rawCode).toLowerCase().replace(/[^a-z0-9_-]/g, '_').slice(0, 64)
      : 'unknown';
    const numericStatus = Number(err && err.status);
    return {
      error: code || 'unknown',
      status: Number.isInteger(numericStatus) && numericStatus >= 100 && numericStatus <= 599
        ? numericStatus
        : null
    };
  }

  function decode(value) {
    if (!value) {
      return null;
    }
    try {
      return decodeURIComponent(value);
    } catch (_) {
      return value;
    }
  }

  // TEMP diagnostics: logs to logcat via the bridge (CHT_WEB_DEBUG); not ingested.
  function debugLog(message) {
    try {
      const bridge = window[BRIDGE_NAME];
      if (bridge && typeof bridge.debug === 'function') {
        bridge.debug(String(message).slice(0, 4000));
      }
    } catch (_) {}
  }

  // Replication only ever exposes the new _rev, so derive the action from it:
  // a first revision (1-...) is an add, a missing rev is an add, otherwise update.
  function writeActionOf(doc) {
    if (doc._deleted === true) {
      return 'delete';
    }
    if (!doc._rev || String(doc._rev).indexOf('1-') === 0) {
      return 'add';
    }
    return 'update';
  }

  // The same write can reach us via the prototype hook and the replication capture;
  // dedupe by doc id + action within a 2 minute window.
  const emittedWrites = new Map();
  const watchedReplications = new WeakSet();
  function alreadyEmitted(key) {
    const ts = now();
    emittedWrites.forEach(function (value, mapKey) {
      if (ts - value > 120000) {
        emittedWrites.delete(mapKey);
      }
    });
    if (emittedWrites.has(key)) {
      return true;
    }
    emittedWrites.set(key, ts);
    return false;
  }

  // Parses CHT webapp routes:
  //   #/tasks, #/tasks/group, #/tasks/<id>
  //   #/contacts, #/contacts/<id>, #/contacts/<id>/report/<form>,
  //   #/contacts/<id>/edit, #/contacts/<id>/add/<type>, #/contacts/add/<type>
  //   #/reports, #/reports/<id>, #/reports/add/<form>, #/reports/edit/<id>
  function route() {
    const hash = window.location.hash || '';
    const raw = hash.charAt(0) === '#' ? hash.slice(1) : (window.location.pathname || '');
    const parts = raw.split('?')[0].split('/').filter(function (part) { return part.length > 0; });
    const result = { group: parts[0] || 'home', screen: 'list', id: null, formId: null };
    if (result.group === 'tasks') {
      // CHT briefly navigates through /tasks/group after a task is submitted. It is
      // a grouped-list route, not an emission id. Treating it as a task detail would
      // replace the completed task's id and duration with this transient route.
      if (parts[1] && parts[1] !== 'group') {
        result.id = decode(parts[1]);
        result.screen = 'detail';
      }
    } else if (result.group === 'contacts') {
      if (parts[1] === 'add') {
        result.screen = 'add';
        result.formId = decode(parts[2]);
      } else if (parts[1]) {
        result.id = decode(parts[1]);
        if (parts[2] === 'report') {
          result.screen = 'form';
          result.formId = decode(parts[3]);
        } else if (parts[2] === 'edit') {
          result.screen = 'edit';
        } else if (parts[2] === 'add') {
          result.screen = 'add';
          result.formId = decode(parts[3]);
        } else {
          result.screen = 'profile';
        }
      }
    } else if (result.group === 'reports') {
      if (parts[1] === 'add') {
        result.screen = 'form';
        result.formId = decode(parts[2]);
      } else if (parts[1] === 'edit') {
        result.screen = 'edit';
        result.id = decode(parts[2]);
      } else if (parts[1]) {
        result.id = decode(parts[1]);
        result.screen = 'detail';
      }
    } else if (parts[1]) {
      result.id = decode(parts[1]);
      result.screen = 'detail';
    }
    return result;
  }

  // The event name is the group; the action is the property; meta becomes the CF event ctx.
  // screen is always null so the Android side uses action as the property.
  function send(name, action, meta) {
    const cleaned = { js_script_version: VERSION };
    for (const key in meta) {
      if (key !== 'js_script_version' && meta[key] !== null && meta[key] !== undefined) {
        cleaned[key] = meta[key];
      }
    }
    const serialized = JSON.stringify({
      group: name,
      action: action,
      screen: null,
      at: new Date().toISOString(),
      meta: cleaned
    });
    try {
      const bridge = window[BRIDGE_NAME];
      if (bridge && typeof bridge.track === 'function') {
        bridge.track(serialized);
      } else if (bridge && typeof bridge.log === 'function') {
        bridge.log(serialized);
      }
    } catch (_) {}
  }

  function closeScreen() {
    screen = null;
  }

  function openScreen() {
    const current = route();
    const key = [current.group, current.screen, current.id, current.formId].join('|');
    if (screen && screen.key === key) {
      return;
    }
    // Leaving a form screen is the submit; capture its fill duration before moving on.
    completeForm(screen);
    const previous = screen;
    screen = {
      key: key,
      group: current.group,
      screen: current.screen,
      id: current.id,
      formId: current.formId,
      enteredAt: now(),
      // remember a task origin so a form launched from a task is still a task event.
      taskId: previous && previous.group === 'tasks' ? previous.id : null
    };
    if (current.group === 'tasks' && current.screen === 'detail' && current.id) {
      lastTask = { id: current.id, at: now(), durationSec: null };
    }
  }

  // A form-fill session is a form/add/edit screen (or a task) the user is on.
  // Duration is measured from entering that screen until they navigate away. The
  // resulting doc reaches us later via replication, so completed sessions are stashed
  // and matched to the doc by form code / contact type / id.
  const completedForms = [];

  function isFormScreen(s) {
    if (!s) {
      return false;
    }
    return s.screen === 'form' || s.screen === 'add' || s.screen === 'edit' ||
      (s.group === 'tasks' && s.screen === 'detail');
  }

  function formMatchesDoc(s, doc) {
    return Boolean(
      (doc.form && s.formId === doc.form) ||
      (doc.contact_type && s.formId === doc.contact_type) ||
      (doc._id && s.contactId === doc._id)
    );
  }

  function sessionOf(s) {
    return {
      group: s.group,
      formId: s.formId,
      contactId: s.id,
      taskId: s.group === 'tasks' ? s.id : s.taskId,
      fromTask: s.group === 'tasks' || Boolean(s.taskId),
      durationSec: Math.round((now() - s.enteredAt) / 1000),
      at: now()
    };
  }

  function completeForm(s) {
    if (!isFormScreen(s)) {
      return;
    }
    const session = sessionOf(s);
    completedForms.push(session);
    while (completedForms.length > 10) {
      completedForms.shift();
    }
    // A form launched from a task: remember its fill time against the task, since the
    // task's report may not match the form session by form code.
    if (session.fromTask && lastTask) {
      lastTask.durationSec = session.durationSec;
      lastTask.at = now();
    }
  }

  // Finds the form session that produced this doc. Does not consume it, so when one
  // submit writes several docs (e.g. a report plus a contact) they all get the same
  // fill duration. Most-recent first; falls back to the live screen if the doc
  // arrives before the user has navigated away.
  function matchForm(doc) {
    const ts = now();
    for (let i = completedForms.length - 1; i >= 0; i--) {
      const f = completedForms[i];
      if (ts - f.at > 600000) {
        completedForms.splice(i, 1);
        continue;
      }
      if (formMatchesDoc(f, doc)) {
        return f;
      }
    }
    if (isFormScreen(screen) && formMatchesDoc(screen, doc)) {
      return sessionOf(screen);
    }
    return null;
  }

  function firstFacility(value) {
    return Array.isArray(value) ? (value.length ? value[0] : null) : value;
  }

  // Resolves the CHW's facility_id from the local user-settings doc
  // (org.couchdb.user:<name>). Runs once against the main local db.
  // Reads the CHT userCtx cookie ({name, roles}); available right after login without
  // the local user-settings doc, so the catalog can be built before it replicates.
  function readUserCtx() {
    try {
      const parts = (document.cookie || '').split(';');
      for (let i = 0; i < parts.length; i++) {
        const part = parts[i].trim();
        if (part.indexOf('userCtx=') === 0) {
          return JSON.parse(decodeURIComponent(part.slice('userCtx='.length)));
        }
      }
    } catch (_) {}
    return null;
  }

  let resolvePending = false;

  // Resolves the CHW facility_id (for site_id) and logs the user catalog. name/roles
  // come from the userCtx cookie; facility_id and the user's contact uuid come from the
  // local user-settings doc. Retried per sync until the facility is found.
  function resolveUser(db) {
    warmContactIndex(db);
    if (userResolved || resolvePending || !db || typeof db.get !== 'function') {
      return;
    }
    const ctx = readUserCtx();
    const name = ctx && ctx.name;
    if (!name) {
      debugLog('userCtx cookie unavailable; will retry');
      return;
    }
    resolvePending = true;
    // The user's uuid (contact_id) lives on the user-settings doc, so identify/catalog
    // wait for it; retried each sync until the doc is available locally.
    db.get('org.couchdb.user:' + name).then(function (doc) {
      resolvePending = false;
      facilityId = str(firstFacility(doc.facility_id));
      debugLog('user-settings found; resolving user catalog');
      sendUserCatalog(db, doc, ctx);
    }).catch(function (err) {
      resolvePending = false;
      debugLog('user-settings not local yet (error=' + errorMeta(err).error + '); will retry');
    });
  }

  function maybeResolveFacility(db) {
    try {
      const dbName = str(db && db.name);
      if (db && !isRemoteName(dbName) && isMainDbName(dbName)) {
        resolveUser(db);
      }
    } catch (_) {}
  }

  // CF requires a known LanguageCode; map CHT codes and drop anything unknown so the
  // catalog is not rejected.
  function langName(code) {
    if (!code) {
      return null;
    }
    const value = String(code).toLowerCase();
    if (value === 'en' || value === 'english') {
      return 'English';
    }
    if (value === 'sw' || value === 'swahili' || value === 'kiswahili') {
      return 'Swahili';
    }
    return null;
  }

  // Builds a CF user catalog from the user-settings doc (+ facility place name) and
  // sends it once via the bridge. Only enum-safe fields are included.
  function sendUserCatalog(db, userDoc, ctx) {
    const username = str((userDoc && userDoc.name) || (ctx && ctx.name));
    if (!username) {
      return;
    }
    // The user's uuid (contact doc id) is the SDK user id; username goes to meta.
    const subjectId = str(userDoc && userDoc.contact_id) || username;
    const rolesArr = (userDoc && userDoc.roles) || (ctx && ctx.roles);
    const roles = Array.isArray(rolesArr) ? rolesArr.join(',') : null;
    const catalog = {
      subject_id: subjectId,
      username: username,
      name: username,
      // Deployment is the Kenya eHIS; CHT has no per-user country, so it is fixed here.
      country: DEFAULT_COUNTRY,
      organization_id: facilityId,
      profession: str(roles),
      language: langName(userDoc && userDoc.language)
    };
    function emit(orgName) {
      catalog.organization_name = str(orgName);
      const cleaned = {};
      for (const key in catalog) {
        if (catalog[key] !== null && catalog[key] !== undefined) {
          cleaned[key] = catalog[key];
        }
      }
      userResolved = true;
      const serialized = JSON.stringify(cleaned);
      debugLog('user identify+catalog resolved');
      try {
        const bridge = window[BRIDGE_NAME];
        if (bridge && typeof bridge.logUser === 'function') {
          bridge.logUser(serialized);
        }
      } catch (_) {}
    }
    // Resolve the facility's display name for organization_name when possible.
    if (facilityId && db && typeof db.get === 'function') {
      db.get(facilityId).then(function (place) {
        emit(place && place.name);
      }).catch(function () {
        emit(null);
      });
    } else {
      emit(null);
    }
  }

  function contactKindFromType(type) {
    if (CLIENT_CONTACT_TYPES.indexOf(type) >= 0) {
      return 'client';
    }
    if (HOUSEHOLD_CONTACT_TYPES.indexOf(type) >= 0) {
      return 'household';
    }
    if (PLACE_CONTACT_TYPES.indexOf(type) >= 0) {
      return 'place';
    }
    return 'unknown';
  }

  function rememberContact(doc, batchContacts) {
    if (!doc || typeof doc._id !== 'string') {
      return null;
    }
    const id = str(doc._id);
    const previous = contactIndex.get(id);
    const isContact = doc.type === 'contact' || doc.type === 'person' ||
      Boolean(doc.contact_type) || Boolean(previous);
    if (!isContact) {
      return null;
    }
    const type = str(doc.contact_type || (doc.type !== 'contact' ? doc.type : null)) ||
      (previous ? previous.type : null);
    let kind = contactKindFromType(type);
    if (kind === 'unknown' && previous) {
      kind = previous.kind;
    }
    const parentId = str(doc.parent && doc.parent._id) || (previous ? previous.parentId : null);
    const info = { id: id, type: type, kind: kind, parentId: parentId };
    contactIndex.set(id, info);
    if (batchContacts) {
      batchContacts.set(id, info);
    }
    return info;
  }

  function contactInfoOf(id, writeContext) {
    const cleanId = str(id);
    if (!cleanId) {
      return null;
    }
    if (writeContext && writeContext.contactsById.has(cleanId)) {
      return writeContext.contactsById.get(cleanId);
    }
    return contactIndex.get(cleanId) || null;
  }

  function rememberContacts(docs) {
    if (!Array.isArray(docs)) {
      return;
    }
    docs.forEach(function (doc) { rememberContact(doc, null); });
  }

  function warmContactIndex(db) {
    if (contactIndexWarmed || contactIndexPending || !db || typeof db.allDocs !== 'function') {
      return;
    }
    contactIndexPending = true;
    db.allDocs({ include_docs: true }).then(function (result) {
      const rows = result && result.rows;
      if (Array.isArray(rows)) {
        rows.forEach(function (row) { rememberContact(row && row.doc, null); });
      }
      contactIndexWarmed = true;
      contactIndexPending = false;
      debugLog('contact id index ready count=' + contactIndex.size);
    }).catch(function () {
      contactIndexPending = false;
      debugLog('contact id index unavailable; using write-batch context');
    });
  }

  function yearFromDob(dob) {
    if (!dob) {
      return null;
    }
    const year = new Date(dob).getFullYear();
    return isNaN(year) ? null : year;
  }

  function ageFromDob(dob) {
    if (!dob) {
      return null;
    }
    const birth = new Date(dob);
    if (isNaN(birth.getTime())) {
      return null;
    }
    const today = new Date();
    let age = today.getFullYear() - birth.getFullYear();
    const monthDelta = today.getMonth() - birth.getMonth();
    if (monthDelta < 0 || (monthDelta === 0 && today.getDate() < birth.getDate())) {
      age--;
    }
    return age >= 0 && age < 200 ? age : null;
  }

  // The contact or place a report is about. This is only a candidate until its
  // contact type is checked; patient_id can identify either a client or household.
  function subjectOf(doc, fields) {
    return str(doc.patient_id || fields.patient_id || fields.patient_uuid ||
      doc.place_id || fields.place_id);
  }

  // A form may explicitly carry the client contact id. Prefer it over patient_id,
  // which identifies the task subject and can therefore be a household contact.
  function explicitClientOf(doc, fields) {
    return str(doc.contact_id || fields.contact_id || doc.client_id || fields.client_id);
  }

  function explicitHouseholdOf(doc, fields) {
    return str(fields.household_id || doc.household_id);
  }

  function placeOf(doc, fields) {
    return str(fields.place_id || doc.place_id);
  }

  function setUnique(map, key, value) {
    if (!key || !value) {
      return;
    }
    if (!map.has(key)) {
      map.set(key, value);
    } else if (map.get(key) !== value) {
      map.set(key, null);
    }
  }

  function taskEmissionOf(doc) {
    if (!doc || doc.type !== 'task') {
      return null;
    }
    const emissionId = doc.emission && doc.emission._id;
    if (emissionId) {
      return longId(emissionId);
    }
    const parts = String(doc._id || '').split('~');
    return parts.length >= 6 && parts[0] === 'task'
      ? longId(parts.slice(2, -1).join('~'))
      : null;
  }

  // CHT submits a registration report and its newly-created contact in the same
  // bulk write / replication batch. Build a structural id-only index so the report
  // can use the person contact's _id and parent household even when patient_id is
  // the household that owned the task. A null household entry means the batch had
  // multiple new clients for that household, so no unsafe guess should be made.
  function writeContextOf(docs) {
    const contactsById = new Map();
    const clientsByHousehold = new Map();
    const taskDocsByEmission = new Map();
    if (!Array.isArray(docs)) {
      return {
        contactsById: contactsById,
        clientsByHousehold: clientsByHousehold,
        taskDocsByEmission: taskDocsByEmission
      };
    }
    docs.forEach(function (doc) {
      const contact = rememberContact(doc, contactsById);
      if (contact && contact.kind === 'client' && contact.parentId) {
        if (writeActionOf(doc) === 'add') {
          setUnique(clientsByHousehold, contact.parentId, contact.id);
        }
      }
      const taskEmission = taskEmissionOf(doc);
      if (taskEmission) {
        setUnique(taskDocsByEmission, taskEmission, longId(doc._id));
      }
    });
    return {
      contactsById: contactsById,
      clientsByHousehold: clientsByHousehold,
      taskDocsByEmission: taskDocsByEmission
    };
  }

  function explicitIdOfKind(id, expectedKind, writeContext) {
    if (!id) {
      return null;
    }
    const info = contactInfoOf(id, writeContext);
    return !info || info.kind === expectedKind ? str(id) : null;
  }

  function knownIdOfKind(id, expectedKind, writeContext) {
    const info = contactInfoOf(id, writeContext);
    return info && info.kind === expectedKind ? str(id) : null;
  }

  function reportContactIds(doc, fields, writeContext, routeContactId) {
    const explicitClient = explicitIdOfKind(explicitClientOf(doc, fields), 'client', writeContext);
    const explicitHousehold = explicitIdOfKind(
      explicitHouseholdOf(doc, fields),
      'household',
      writeContext
    );
    const subject = subjectOf(doc, fields);
    const place = placeOf(doc, fields);
    let clientId = explicitClient;
    let householdId = explicitHousehold;

    if (!householdId) {
      householdId = knownIdOfKind(place, 'household', writeContext) ||
        knownIdOfKind(subject, 'household', writeContext) ||
        knownIdOfKind(routeContactId, 'household', writeContext);
    }

    if (!clientId && writeContext && householdId &&
        writeContext.clientsByHousehold.has(householdId)) {
      clientId = writeContext.clientsByHousehold.get(householdId);
    }
    if (!clientId) {
      clientId = knownIdOfKind(subject, 'client', writeContext) ||
        knownIdOfKind(routeContactId, 'client', writeContext);
    }

    const clientInfo = contactInfoOf(clientId, writeContext);
    if (clientInfo && clientInfo.kind !== 'client') {
      clientId = null;
    } else if (clientInfo && clientInfo.parentId) {
      const parentInfo = contactInfoOf(clientInfo.parentId, writeContext);
      if (!parentInfo || parentInfo.kind === 'household') {
        householdId = clientInfo.parentId;
      }
    }

    const householdInfo = contactInfoOf(householdId, writeContext);
    if (householdInfo && householdInfo.kind !== 'household') {
      householdId = null;
    }
    if (clientId && householdId && clientId === householdId) {
      clientId = null;
    }
    return { clientId: clientId, householdId: householdId };
  }

  function isAppDoc(doc) {
    if (!doc || typeof doc !== 'object' || typeof doc._id !== 'string') {
      return false;
    }
    if (doc._id.indexOf('_design') === 0 || doc._id.indexOf('_local') === 0) {
      return false;
    }
    if (!doc.type && !doc.form && !doc.contact_type) {
      return false;
    }
    return IGNORED_DOCUMENT_TYPES.indexOf(doc.type) < 0;
  }

  function onDocWrite(doc, method, dbName, writeContext) {
    if (!doc || typeof doc._id !== 'string') {
      return;
    }
    debugLog('onDocWrite ' + method + ' id=' + doc._id + ' type=' + doc.type +
      ' form=' + doc.form + ' contact_type=' + doc.contact_type + ' app=' + isAppDoc(doc));
    if (!isAppDoc(doc)) {
      return;
    }
    const fields = doc.fields || {};
    const isReport = doc.type === 'data_record' || Boolean(doc.form);
    const writeAction = writeActionOf(doc);
    if (alreadyEmitted(doc._id + '|' + writeAction)) {
      return;
    }

    // The form-fill session gives both the real duration and where it was filled.
    const form = matchForm(doc);
    const durationSec = form ? form.durationSec : null;
    const filledIn = form ? form.group : (screen ? screen.group : null);

    if (isReport) {
      const formType = str(doc.form);
      const routeContactId = form && !form.fromTask ? form.contactId : null;
      const contactIds = reportContactIds(doc, fields, writeContext, routeContactId);
      const subject = contactIds.clientId;
      const household = contactIds.householdId;

      // A task completion: a recent task whose emission id embeds this form code, or a
      // form we saw launched from a task. The emission id is the task_id.
      const recentTask = lastTask && (now() - lastTask.at) < 600000 ? lastTask : null;
      const taskByForm = Boolean(recentTask && formType && String(recentTask.id).indexOf(formType) >= 0);
      if (writeAction === 'add' && (taskByForm || (form && form.fromTask) || filledIn === 'tasks')) {
        const taskDuration = durationSec != null ? durationSec : (recentTask ? recentTask.durationSec : null);
        const taskId = longId((recentTask && recentTask.id) ||
          (form && (form.taskId || form.contactId)) ||
          (screen && screen.group === 'tasks' ? screen.id : null));
        const taskDocId = writeContext && taskId
          ? writeContext.taskDocsByEmission.get(taskId)
          : null;
        send(EVENT_NAMES.TASK, 'add', {
          client_id: subject,
          household_id: household,
          task_id: taskId,
          task_doc_id: taskDocId,
          report_id: str(doc._id),
          type: formType,
          site_id: facilityId,
          duration_sec: taskDuration
        });
        return;
      }

      if (writeAction === 'add' && filledIn === 'contacts') {
        send(EVENT_NAMES.CLIENT_FORM, 'add', {
          client_id: subject,
          household_id: household,
          type: formType,
          form_id: str(doc._id),
          site_id: facilityId,
          duration_sec: durationSec
        });
        return;
      }

      send(EVENT_NAMES.REPORT, writeAction, {
        client_id: subject,
        household_id: household,
        report_id: str(doc._id),
        type: formType,
        site_id: facilityId,
        duration_sec: durationSec
      });
      return;
    }

    // Client events are only for person/client contact types. Household and site
    // contacts remain available as context dimensions but never become client_id.
    const contact = rememberContact(doc, writeContext && writeContext.contactsById);
    if (!contact || contact.kind !== 'client') {
      return;
    }
    const parentInfo = contactInfoOf(contact.parentId, writeContext);
    const householdId = !parentInfo || parentInfo.kind === 'household'
      ? contact.parentId
      : null;
    send(EVENT_NAMES.CLIENT, writeAction, {
      client_id: contact.id,
      household_id: householdId,
      site_id: facilityId,
      type: str(doc.contact_type || doc.type) || 'person',
      age: ageFromDob(doc.date_of_birth),
      dob: yearFromDob(doc.date_of_birth),
      duration_sec: durationSec
    });
  }

  // Replication writes incoming docs with new_edits === false; those are sync,
  // not user actions, and must not be tracked.
  function isLocalWrite(options) {
    return !(options && options.new_edits === false);
  }

  function nameOf(value) {
    return str(typeof value === 'string' ? value : (value && value.name));
  }

  function isRemoteName(name) {
    return Boolean(name) && (name.indexOf('http:') === 0 || name.indexOf('https:') === 0);
  }

  // Meta databases (e.g. medic-user-x-meta) sync constantly and are noise.
  function isMetaDb(name) {
    return Boolean(name) && (name.slice(-5) === '-meta' || name.slice(-5) === '_meta');
  }

  function isMainDbName(name) {
    return Boolean(name) && name.indexOf('medic-user-') === 0 && !isMetaDb(name);
  }

  function watchReplication(replication, direction, dbName) {
    if (!replication || typeof replication.on !== 'function' || isMetaDb(dbName) ||
        watchedReplications.has(replication)) {
      return;
    }
    watchedReplications.add(replication);
    // The prototype write hook does not fire for every CHT write, so use the local
    // replication database to warm contact types and resolve the CHW facility.
    if (isMainDbName(dbName) && !isRemoteName(dbName) && window.PouchDB) {
      try { resolveUser(new window.PouchDB(dbName)); } catch (_) {}
    }
    const startedAt = now();
    send(EVENT_NAMES.SYNC, 'start', { direction: direction, db: dbName });
    // Reliable write capture: docs pushed to the server are the user's local writes.
    replication.on('change', function (info) {
      try {
        let docs = info && info.docs;
        let changeDir = direction;
        if (!docs && info && info.change && info.change.docs) {
          docs = info.change.docs;
          changeDir = info.direction || direction;
        }
        rememberContacts(docs);
        debugLog('repl change dir=' + changeDir + ' docs=' + (docs ? docs.length : 'none'));
        if (docs && changeDir === 'push') {
          const writeContext = writeContextOf(docs);
          docs.forEach(function (doc) {
            onDocWrite(doc, 'replicate', dbName, writeContext);
          });
        }
      } catch (_) {}
    });
    replication.on('denied', function (err) {
      send(EVENT_NAMES.SYNC, 'denied', Object.assign({
        direction: direction,
        db: dbName,
        duration_ms: now() - startedAt
      }, errorMeta(err)));
    });
    replication.on('error', function (err) {
      send(EVENT_NAMES.SYNC, 'error', Object.assign({
        direction: direction,
        db: dbName,
        duration_ms: now() - startedAt
      }, errorMeta(err)));
    });
    replication.on('complete', function () {
      send(EVENT_NAMES.SYNC, 'complete', {
        direction: direction,
        db: dbName,
        duration_ms: now() - startedAt
      });
    });
  }

  // db.replicate.to / db.replicate.from delegate to the static PouchDB.replicate,
  // and db.sync delegates to PouchDB.sync, so patching the statics covers all syncs.
  function patchReplication(pouch) {
    if (pouch.__cfSdkReplicationPatched) {
      return;
    }
    const originalReplicate = pouch.replicate;
    if (typeof originalReplicate === 'function') {
      pouch.replicate = function (source, target) {
        const replication = originalReplicate.apply(this, arguments);
        try {
          const sourceName = nameOf(source);
          const targetName = nameOf(target);
          const direction = isRemoteName(targetName) ? 'push' : (isRemoteName(sourceName) ? 'pull' : 'local');
          // Report the local db name only, never the remote URL.
          const dbName = isRemoteName(sourceName) ? targetName : sourceName;
          watchReplication(replication, direction, dbName);
        } catch (_) {}
        return replication;
      };
    }
    const originalSync = pouch.sync;
    if (typeof originalSync === 'function') {
      pouch.sync = function (source, target) {
        const replication = originalSync.apply(this, arguments);
        try {
          const sourceName = nameOf(source);
          const dbName = isRemoteName(sourceName) ? nameOf(target) : sourceName;
          watchReplication(replication, 'sync', dbName);
        } catch (_) {}
        return replication;
      };
    }
    pouch.__cfSdkReplicationPatched = true;
  }

  function markWrapped(fn, marker) {
    try {
      Object.defineProperty(fn, marker, { value: true });
    } catch (_) {
      fn[marker] = true;
    }
    return fn;
  }

  function markPatched(target, marker) {
    try {
      Object.defineProperty(target, marker, { value: true });
    } catch (_) {
      target[marker] = true;
    }
  }

  function hasOwnMarker(target, marker) {
    return Boolean(target && Object.prototype.hasOwnProperty.call(target, marker));
  }

  function patchWriteMethods(target) {
    if (!target || hasOwnMarker(target, '__cfSdkWritesPatched')) {
      return false;
    }

    ['put', 'post'].forEach(function (methodName) {
      const original = target[methodName];
      if (typeof original !== 'function' || original.__cfSdkWriteWrapped) {
        return;
      }
      target[methodName] = markWrapped(function () {
        try {
          const doc = arguments[0];
          debugLog('hook ' + methodName + ' db=' + str(this && this.name));
          maybeResolveFacility(this);
          onDocWrite(doc, methodName, str(this && this.name), writeContextOf([doc]));
        } catch (_) {}
        return original.apply(this, arguments);
      }, '__cfSdkWriteWrapped');
    });

    const originalRemove = target.remove;
    if (typeof originalRemove === 'function' && !originalRemove.__cfSdkWriteWrapped) {
      target.remove = markWrapped(function () {
        try {
          debugLog('hook remove db=' + str(this && this.name));
          const docOrId = arguments[0];
          const base = (docOrId && typeof docOrId === 'object')
            ? docOrId
            : { _id: docOrId, _rev: arguments[1], type: 'unknown' };
          const removed = Object.assign({}, base, { _deleted: true });
          onDocWrite(removed, 'remove', str(this && this.name), writeContextOf([removed]));
        } catch (_) {}
        return originalRemove.apply(this, arguments);
      }, '__cfSdkWriteWrapped');
    }

    const originalBulkDocs = target.bulkDocs;
    if (typeof originalBulkDocs === 'function' && !originalBulkDocs.__cfSdkWriteWrapped) {
      target.bulkDocs = markWrapped(function () {
        try {
          const body = arguments[0];
          const options = Array.isArray(body) ? arguments[1] : body;
          const docs = Array.isArray(body) ? body : body && body.docs;
          if (Array.isArray(docs) && isLocalWrite(options)) {
            const dbName = str(this && this.name);
            const writeContext = writeContextOf(docs);
            debugLog('hook bulkDocs db=' + dbName + ' count=' + docs.length);
            maybeResolveFacility(this);
            docs.forEach(function (doc) {
              onDocWrite(doc, 'bulkDocs', dbName, writeContext);
            });
          }
        } catch (_) {}
        return originalBulkDocs.apply(this, arguments);
      }, '__cfSdkWriteWrapped');
    }

    markPatched(target, '__cfSdkWritesPatched');
    return true;
  }

  // PouchDB 9 installs its API as own properties on each database instance. CHT then
  // wraps those functions again to run them outside Angular. Both layers shadow the
  // PouchDB prototype, so prototype-only instrumentation never sees real CHT writes.
  // Replication still delegates to the patched PouchDB statics and must not be wrapped
  // here as well, otherwise one sync produces two sets of lifecycle events.
  function patchPouchInstance(db) {
    const dbName = nameOf(db);
    if (!db || hasOwnMarker(db, '__cfSdkInstancePatched') ||
        !isMainDbName(dbName) || isRemoteName(dbName)) {
      return false;
    }

    patchWriteMethods(db);
    markPatched(db, '__cfSdkInstancePatched');
    maybeResolveFacility(db);
    debugLog('patched CHT PouchDB instance db=' + dbName);
    return true;
  }

  function patchChtDatabaseService() {
    const core = window.CHTCore;
    const service = core && (core.dbService || core.DB);
    if (!service) {
      return false;
    }

    const cache = service.cache;
    if (cache && typeof cache === 'object') {
      Object.keys(cache).forEach(function (key) {
        patchPouchInstance(cache[key]);
      });
    }

    const originalGet = service.get;
    if (typeof originalGet === 'function' && !originalGet.__cfSdkGetWrapped) {
      service.get = markWrapped(function () {
        const db = originalGet.apply(this, arguments);
        patchPouchInstance(db);
        return db;
      }, '__cfSdkGetWrapped');
    }

    return Boolean(service.get && service.get.__cfSdkGetWrapped);
  }

  function patchPouchDb() {
    const pouch = window.PouchDB;
    if (!pouch || !pouch.prototype) {
      return false;
    }

    if (!pouch.prototype.__cfSdkPatched) {
      patchReplication(pouch);
      patchWriteMethods(pouch.prototype);
      markPatched(pouch.prototype, '__cfSdkPatched');
      debugLog('patched PouchDB prototype and static replication');
    }

    return patchChtDatabaseService();
  }

  function patchNavigation() {
    ['pushState', 'replaceState'].forEach(function (methodName) {
      const original = history[methodName];
      history[methodName] = function () {
        const result = original.apply(this, arguments);
        setTimeout(function () { openScreen(); }, 0);
        return result;
      };
    });
    window.addEventListener('hashchange', function () { openScreen(); }, true);
    window.addEventListener('popstate', function () { openScreen(); }, true);
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'hidden') {
        closeScreen();
      } else {
        openScreen();
      }
    });
    window.addEventListener('pagehide', function () { closeScreen(); });
  }

  patchNavigation();
  patchPouchDb();
  openScreen();
  debugLog('cf-sdk instrumentation v' + VERSION + ' initialised');

  // PouchDB may not exist yet when this script is injected; retry for up to a minute.
  let pouchPollAttempts = 0;
  const pouchPoll = setInterval(function () {
    const ready = patchPouchDb();
    pouchPollAttempts++;
    if (ready || pouchPollAttempts > 60) {
      clearInterval(pouchPoll);
    }
  }, 1000);

  window.__cfSdkChtInstrumentation = {
    version: VERSION,
    bridgeContract: BRIDGE_CONTRACT,
    send: send,
    route: route
  };
})();
