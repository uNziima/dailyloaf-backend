// ============================================================
// DAILY LOAF DELIVERY OS — app.js
// ============================================================

// ── Backend URL ───────────────────────────────────────────
 const BACKEND = 'https://dailyloaf-backend-production.up.railway.app';
//const BACKEND = 'http://localhost:8080';

// ── State ─────────────────────────────────────────────────
let map;
let stops          = [];   // all delivery stops for the day
let markers        = {};   // orderId → Google Maps marker
let geocodedStops  = [];   // parallel array of LatLng per stop
let currentStop    = null; // currently selected stop object
let cashTotal      = 0;    // running cash total
let cashCollected  = {};   // orderId → true/false
let deliveryDay    = '';   // "Monday", "Wednesday", or "Friday"
let activeFilter   = 'All';
let routeRenderer  = null;
let geocoder       = null;

// ── Access Control ────────────────────────────────────────
const urlParams = new URLSearchParams(window.location.search);
const accessKey = urlParams.get('key');

// ── Startup ───────────────────────────────────────────────
window.addEventListener('DOMContentLoaded', async () => {

  // No key in URL → access denied immediately
  if (!accessKey) {
    showAccessDenied();
    return;
  }

  // Show the app shell
  document.getElementById('app').classList.remove('hidden');

  // Set today's delivery day and display date
  // Allow manual day override via URL for testing
// e.g. ?key=xxx&day=Friday
const dayOverride = urlParams.get('day');
deliveryDay = dayOverride || getTodayDeliveryDay();
  setDateDisplay();

  // Fetch the Maps API key from backend then load Maps
  await loadGoogleMaps();
});

// ── Access Denied ─────────────────────────────────────────
function showAccessDenied() {
  document.getElementById('access-denied').classList.remove('hidden');
}

// ── Delivery Day Detection (SAST) ─────────────────────────
function getTodayDeliveryDay() {
  const now    = new Date();
  const saTime = new Date(
    now.toLocaleString('en-US', { timeZone: 'Africa/Johannesburg' })
  );
  const day = saTime.getDay(); // 0=Sun 1=Mon 2=Tue 3=Wed 4=Thu 5=Fri 6=Sat
  const map = { 1: 'Monday', 3: 'Wednesday', 5: 'Friday' };
  return map[day] || null;
}

function setDateDisplay() {
  const now    = new Date();
  const saTime = new Date(
    now.toLocaleString('en-US', { timeZone: 'Africa/Johannesburg' })
  );
  const opts = {
    weekday: 'long', day: 'numeric',
    month: 'short', year: 'numeric'
  };
  document.getElementById('delivery-date').textContent =
    saTime.toLocaleDateString('en-ZA', opts);
}

// ── Load Google Maps ──────────────────────────────────────
// Fetches the Maps API key from the backend /config endpoint
// then injects the Google Maps script dynamically.
async function loadGoogleMaps() {
  try {
    const res = await fetch(`${BACKEND}/config?key=${accessKey}`);

    if (!res.ok) {
      showAccessDenied();
      return;
    }

    const config = await res.json();

    // Inject Google Maps script with the key from backend
    const script    = document.createElement('script');
    script.src      = `https://maps.googleapis.com/maps/api/js` +
                      `?key=${config.mapsKey}&callback=initMap&libraries=geometry`;
    script.async    = true;
    script.defer    = true;
    document.head.appendChild(script);

  } catch (err) {
    console.error('Config fetch failed:', err);
    showToast('Could not connect to server.');
  }
}

// ── Map Initialisation (Google Maps callback) ─────────────
// Called automatically by Google Maps after the script loads.
async function initMap() {

  // Madadeni, KwaZulu-Natal approximate centre
  const centre = { lat: -27.7833, lng: 29.9500 };

  map = new google.maps.Map(document.getElementById('map'), {
    zoom: 14,
    center: centre,
    mapTypeControl:      false,
    streetViewControl:   false,
    fullscreenControl:   false,
    zoomControlOptions: {
      position: google.maps.ControlPosition.RIGHT_CENTER
    }
  });

  geocoder = new google.maps.Geocoder();

  // Route line renderer — uses our own markers so suppress default ones
  routeRenderer = new google.maps.DirectionsRenderer({
    map,
    suppressMarkers: true,
    polylineOptions: {
      strokeColor:   '#1a3c1a',
      strokeWeight:  4,
      strokeOpacity: 0.65
    }
  });

  await loadDeliveries();
}

// ── Load Deliveries ───────────────────────────────────────
async function loadDeliveries() {
  if (!deliveryDay) {
    document.getElementById('no-orders').classList.remove('hidden');
    document.getElementById('progress-text').textContent = 'No delivery today';
    return;
  }

  document.getElementById('progress-text').textContent = 'Loading...';

  try {
    const res  = await fetch(
      `${BACKEND}/deliveries?day=${deliveryDay}&key=${accessKey}`
    );
    const data = await res.json();

    if (!data || data.length === 0) {
      document.getElementById('no-orders').classList.remove('hidden');
      document.getElementById('progress-text').textContent = '0 deliveries';
      return;
    }

    stops = data;
    updateProgress();
    await plotStops();

  } catch (err) {
    console.error('Load deliveries failed:', err);
    showToast('Failed to load deliveries.');
  }
}

// ── Plot All Stops on the Map ─────────────────────────────
async function plotStops() {
  // Geocode every address — builds geocodedStops[] in parallel order
  const addresses = stops.map(s =>
    `${s.houseNumber} ${s.section}, Madadeni, KwaZulu-Natal, South Africa`
  );

  // Place a numbered marker for each stop
  stops.forEach((stop, i) => {
    const position = geocodedStops[i];
    if (!position) return;

    const marker = buildMarker(i + 1, position, stop, 'pending');
    markers[stop.orderId] = marker;
    marker.addListener('click', () => selectStop(i));
  });

  fitMapToMarkers();
  drawRoute();
}

// Approximate centres for each section in Madadeni.
// Used as fallback when a house number can't be geocoded.
function sectionCentre(address) {
  const centres = {
    'Ikwezi':    { lat: -27.7820, lng: 29.9480 },
    'Section 1': { lat: -27.7800, lng: 29.9460 },
    'Section 2': { lat: -27.7780, lng: 29.9500 },
    'Section 3': { lat: -27.7760, lng: 29.9520 },
    'Section 4': { lat: -27.7740, lng: 29.9540 },
    'Section 5': { lat: -27.7720, lng: 29.9560 },
    'Section 6': { lat: -27.7700, lng: 29.9580 },
    'Section 7': { lat: -27.7680, lng: 29.9600 },
  };
  for (const [section, centre] of Object.entries(centres)) {
    if (address.includes(section)) {
      return new google.maps.LatLng(centre.lat, centre.lng);
    }
  }
  return new google.maps.LatLng(-27.7833, 29.9500);
}

// ── Custom SVG Markers ────────────────────────────────────
// Each marker is a numbered teardrop pin drawn as inline SVG.
// Colour reflects the delivery state.
function buildMarker(number, position, stop, state) {
  const palette = {
    'pending':       { fill: '#1a5276', text: '#ffffff' },
    'active':        { fill: '#e67e22', text: '#ffffff' },
    'delivered':     { fill: '#1e8449', text: '#ffffff' },
    'not-delivered': { fill: '#c0392b', text: '#ffffff' },
  };

  const c     = palette[state] || palette['pending'];
  const isCash = stop.paymentMethod === 'Cash';
  // Cash stops show "R" prefix on the pin so you know before you arrive
  const label = isCash ? `R${number}` : `${number}`;
  const fSize = label.length > 2 ? 9 : 12;

  const svg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="36" height="46" viewBox="0 0 36 46">
      <ellipse cx="18" cy="42" rx="6" ry="3" fill="rgba(0,0,0,0.18)"/>
      <path d="M18 2C9.2 2 2 9.2 2 18c0 11.8 16 40 16 40s16-28.2 16-40C34 9.2 26.8 2 18 2z"
            fill="${c.fill}"/>
      <circle cx="18" cy="18" r="10" fill="rgba(255,255,255,0.15)"/>
      <text x="18" y="23" text-anchor="middle"
            font-family="-apple-system,Arial,sans-serif"
            font-size="${fSize}" font-weight="700"
            fill="${c.text}">${label}</text>
    </svg>
  `;

  return new google.maps.Marker({
    position,
    map,
    icon: {
      url:         'data:image/svg+xml;charset=UTF-8,' + encodeURIComponent(svg),
      scaledSize:  new google.maps.Size(36, 46),
      anchor:      new google.maps.Point(18, 46)
    },
    title:  `${stop.firstName} ${stop.surname} — ${stop.houseNumber} ${stop.section}`,
    zIndex: state === 'active' ? 10 : 1
  });
}

// Rebuilds a marker in a new state (colour change on tap/action).
function refreshMarker(orderId, newState) {
  const stop = stops.find(s => s.orderId === orderId);
  const idx  = stops.indexOf(stop);
  if (!stop || !geocodedStops[idx]) return;

  if (markers[orderId]) markers[orderId].setMap(null);

  const marker = buildMarker(idx + 1, geocodedStops[idx], stop, newState);
  markers[orderId] = marker;
  marker.addListener('click', () => selectStop(idx));
}

// ── Route Drawing ─────────────────────────────────────────
// Draws the optimised driving route through all geocoded stops.
function drawRoute() {
  const valid = geocodedStops.filter(Boolean);
  if (valid.length < 2) return;

  const directionsService = new google.maps.DirectionsService();

  directionsService.route({
    origin:             valid[0],
    destination:        valid[valid.length - 1],
    waypoints:          valid.slice(1, -1).map(loc => ({
                          location: loc, stopover: false
                        })),
    travelMode:         google.maps.TravelMode.DRIVING,
    optimizeWaypoints:  true
  }, (result, status) => {
    if (status === 'OK') routeRenderer.setDirections(result);
  });
}

function fitMapToMarkers() {
  const bounds = new google.maps.LatLngBounds();
  geocodedStops.forEach(pos => { if (pos) bounds.extend(pos); });
  if (!bounds.isEmpty()) map.fitBounds(bounds);
}

// ── Stop Selection ────────────────────────────────────────
// Called when a marker is tapped — populates the stop panel.
function selectStop(index) {
  const stop = stops[index];
  if (!stop) return;

  // Restore previous active marker to its real state
  if (currentStop) {
    const prevState = currentStop.delivered      ? 'delivered'
                    : currentStop.notDelivered   ? 'not-delivered'
                    : 'pending';
    refreshMarker(currentStop.orderId, prevState);
  }

  currentStop = stop;
  refreshMarker(stop.orderId, 'active');

  // ── Populate stop panel ──────────────────────────────
  document.getElementById('stop-number').textContent = index + 1;
  document.getElementById('stop-name').textContent   =
    `${stop.firstName} ${stop.surname}`;

  const badge = document.getElementById('stop-status-badge');
  if (stop.delivered) {
    badge.textContent = 'Delivered';
    badge.className   = 'delivered';
  } else if (stop.notDelivered) {
    badge.textContent = 'Not Delivered';
    badge.className   = 'not-delivered';
  } else {
    badge.textContent = 'Pending';
    badge.className   = '';
  }

  document.getElementById('stop-address').textContent =
    `${stop.houseNumber}, ${stop.section}`;

  const loaves = [];
  if (parseInt(stop.whiteLoaves) > 0) loaves.push(`${stop.whiteLoaves} white`);
  if (parseInt(stop.brownLoaves) > 0) loaves.push(`${stop.brownLoaves} brown`);
  document.getElementById('stop-order').textContent =
    `${loaves.join(' + ')}  —  R${stop.amount}`;

  document.getElementById('stop-payment').textContent = stop.paymentMethod;

  const notesRow = document.getElementById('stop-notes-row');
  const notesVal = (stop.deliveryNotes || '').trim();
  if (notesVal && !notesVal.startsWith('Delivered') && !notesVal.startsWith('Not delivered')) {
    document.getElementById('stop-notes').textContent = notesVal;
    notesRow.classList.remove('hidden');
  } else {
    notesRow.classList.add('hidden');
  }

  // ── Cash button ──────────────────────────────────────
  const cashContainer = document.getElementById('cash-container');
  if (stop.paymentMethod === 'Cash') {
    cashContainer.classList.remove('hidden');
    const cashBtn = document.getElementById('cash-btn');
    if (cashCollected[stop.orderId]) {
      cashBtn.classList.add('collected');
      cashBtn.textContent = `Cash Collected - R${stop.amount}`;
    } else {
      cashBtn.classList.remove('collected');
      cashBtn.textContent = `Cash Collected - R${stop.amount}`;
    }
  } else {
    cashContainer.classList.add('hidden');
  }

  // ── Disable buttons if already actioned ──────────────
  const actioned = stop.delivered || stop.notDelivered;
  ['.deliver-btn', '.not-deliver-btn', '.outside-btn'].forEach(sel => {
    const btn = document.querySelector(sel);
    btn.disabled      = actioned;
    btn.style.opacity = actioned ? '0.4' : '1';
  });

  document.getElementById('stop-panel').classList.remove('hidden');
  
  // Show payment button for pending cash orders
    const payContainer = document.getElementById('pay-container');
    if (stop.status === 'PENDING_PAYMENT' || !stop.status) {
        payContainer.classList.remove('hidden');
    } else {
        payContainer.classList.add('hidden');
    }
}

// ── Action: We're Outside ─────────────────────────────────
async function sendOutside() {
  if (!currentStop) return;

  try {
    const res = await fetch(`${BACKEND}/send`, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        to:      currentStop.whatsapp,
        message: `Hi ${currentStop.firstName}, we're outside your gate with your bread. Come collect when you're ready.`
      })
    });

    showToast(res.ok
      ? `📍 ${currentStop.firstName} notified`
      : 'Failed to send message');

  } catch (err) {
    showToast('Network error — try again');
  }
}

// ── Action: Delivered ─────────────────────────────────────
async function markDelivered() {
  if (!currentStop || currentStop.delivered) return;

  const btn = document.querySelector('.deliver-btn');
  btn.textContent = 'Updating...';
  btn.disabled    = true;

  try {
    const res = await fetch(`${BACKEND}/deliver`, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        orderId:    currentStop.orderId,
        customerId: currentStop.customerId,
        firstName:  currentStop.firstName,
        whatsapp:   currentStop.whatsapp
      })
    });

    if (res.ok) {
      currentStop.delivered = true;
      refreshMarker(currentStop.orderId, 'delivered');

      const badge = document.getElementById('stop-status-badge');
      badge.textContent = 'Delivered';
      badge.className   = 'delivered';

      btn.textContent   = '✓ Delivered';
      btn.disabled      = true;
      btn.style.opacity = '0.4';

      document.querySelector('.not-deliver-btn').disabled      = true;
      document.querySelector('.not-deliver-btn').style.opacity = '0.4';
      document.querySelector('.outside-btn').disabled          = true;
      document.querySelector('.outside-btn').style.opacity     = '0.4';

      updateProgress();
      showToast(`✓ ${currentStop.firstName} — delivered`);

    } else {
      btn.textContent = '✓ Delivered';
      btn.disabled    = false;
      showToast('Failed — try again');
    }

  } catch (err) {
    btn.textContent = '✓ Delivered';
    btn.disabled    = false;
    showToast('Network error — try again');
  }
}

// ── Action: Not Delivered modal ───────────────────────────
function showNotDeliveredModal() {
  document.getElementById('not-delivered-modal')
          .classList.remove('hidden');
}

function hideNotDeliveredModal() {
  document.getElementById('not-delivered-modal')
          .classList.add('hidden');
}

async function confirmNotDelivered(reason) {
  hideNotDeliveredModal();
  if (!currentStop) return;

  try {
    const res = await fetch(`${BACKEND}/not-delivered`, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        orderId:   currentStop.orderId,
        firstName: currentStop.firstName,
        whatsapp:  currentStop.whatsapp,
        reason:    reason
      })
    });

    if (res.ok) {
      currentStop.notDelivered = true;
      refreshMarker(currentStop.orderId, 'not-delivered');

      const badge = document.getElementById('stop-status-badge');
      badge.textContent = 'Not Delivered';
      badge.className   = 'not-delivered';

      ['.deliver-btn', '.not-deliver-btn', '.outside-btn'].forEach(sel => {
        const btn = document.querySelector(sel);
        btn.disabled      = true;
        btn.style.opacity = '0.4';
      });

      updateProgress();
      showToast(`Marked — ${reason}`);
    }

  } catch (err) {
    showToast('Network error — try again');
  }
}

// ── Action: Cash Toggle ───────────────────────────────────
function toggleCash() {
  if (!currentStop) return;

  const btn    = document.getElementById('cash-btn');
  const amount = parseInt(currentStop.amount) || 0;

  if (cashCollected[currentStop.orderId]) {
    cashCollected[currentStop.orderId] = false;
    cashTotal -= amount;
    btn.classList.remove('collected');
    btn.textContent = `Cash Collected — R${amount}`;
  } else {
    cashCollected[currentStop.orderId] = true;
    cashTotal += amount;
    btn.classList.add('collected');
    btn.textContent = `Cash Collected — R${amount}`;
  }

  document.getElementById('cash-total').textContent = cashTotal;
}

// ── Broadcast ─────────────────────────────────────────────
async function broadcastOnTheWay() {
  if (!deliveryDay) {
    showToast('No delivery day detected');
    return;
  }

  const btn = document.getElementById('broadcast-btn');
  btn.textContent = 'Sending...';
  btn.disabled    = true;

  try {
    const res  = await fetch(
      `${BACKEND}/broadcast?day=${deliveryDay}&key=${accessKey}`,
      { method: 'POST' }
    );
    const data = await res.json();

    showToast(`Sent to ${data.sent} customers`);
    btn.textContent = `Sent to ${data.sent} customers`;

  } catch (err) {
    btn.textContent = 'Start Deliveries — Send "On the Way"';
    btn.disabled    = false;
    showToast('Failed to broadcast');
  }
}

// ── Share Route ───────────────────────────────────────────
function shareRoute() {
  const url = window.location.href;

  if (navigator.share) {
    navigator.share({
      title: 'Daily Loaf Delivery OS',
      text:  `Daily Loaf delivery route — ${deliveryDay}`,
      url
    });
  } else {
    navigator.clipboard.writeText(url).then(() => {
      showToast('Link copied to clipboard');
    }).catch(() => {
      showToast(url);
    });
  }
}

// ── Progress Bar ──────────────────────────────────────────
function updateProgress() {
  const total     = stops.length;
  const delivered = stops.filter(s => s.delivered).length;
  const pct       = total > 0 ? (delivered / total) * 100 : 0;

  document.getElementById('progress-fill').style.width = pct + '%';
  document.getElementById('progress-text').textContent =
    `${delivered} of ${total} delivered`;
}

// ── Toast ─────────────────────────────────────────────────
function showToast(message) {
  let toast = document.getElementById('toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'toast';
    toast.style.cssText = [
      'position:fixed', 'bottom:90px', 'left:50%',
      'transform:translateX(-50%)',
      'background:#1a3c1a', 'color:white',
      'padding:10px 20px', 'border-radius:20px',
      'font-size:13px', 'font-weight:600',
      'z-index:9999', 'box-shadow:0 4px 12px rgba(0,0,0,0.3)',
      'transition:opacity 0.3s', 'white-space:nowrap',
      'max-width:90vw', 'text-align:center'
    ].join(';');
    document.body.appendChild(toast);
  }
  toast.textContent  = message;
  toast.style.opacity = '1';
  setTimeout(() => { toast.style.opacity = '0'; }, 3000);
}

async function plotStops() {
    geocodedStops = [];

    stops.forEach((stop, i) => {
        let position = null;

        // Use backend-provided coordinates if available
        if (stop.lat && stop.lng &&
            stop.lat !== '' && stop.lng !== '') {
            position = new google.maps.LatLng(
                parseFloat(stop.lat),
                parseFloat(stop.lng)
            );
        } else {
            // Fall back to section centre
            position = getSectionCentre(stop.section);
        }

        geocodedStops.push(position);

        if (!position) return;
        const marker = buildMarker(i + 1, position, stop, 'pending');
        markers[stop.orderId] = marker;
        marker.addListener('click', () => selectStop(i));
    });

    fitMapToMarkers();
    drawRoute();
}

async function confirmPayment() {
    if (!currentStop) return;

    const btn = document.querySelector('.pay-btn');
    btn.textContent = 'Confirming...';
    btn.disabled    = true;

    try {
        const res = await fetch(`${BACKEND}/pay`, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                orderId:    currentStop.orderId,
                customerId: currentStop.customerId,
                firstName:  currentStop.firstName,
                whatsapp:   currentStop.whatsapp,
                deliveryDay: currentStop.deliveryDay
            })
        });

        if (res.ok) {
            currentStop.paid = true;
            document.getElementById('pay-container')
                    .classList.add('hidden');
            showToast(`✓ Payment confirmed for ${currentStop.firstName}`);
        } else {
            btn.textContent = '✓ Payment Received';
            btn.disabled    = false;
            showToast('Failed — try again');
        }

    } catch (err) {
        btn.textContent = '✓ Payment Received';
        btn.disabled    = false;
        showToast('Network error — try again');
    }
}

function getSectionCentre(section) {
    const s = (section || '').trim().toLowerCase();
    const centres = {
        'ikwezi':    { lat: -27.7820, lng: 29.9480 },
        'ikhwezi':   { lat: -27.7820, lng: 29.9480 },
        'section 1': { lat: -27.7800, lng: 29.9460 },
        'section 2': { lat: -27.7780, lng: 29.9500 },
        'section 3': { lat: -27.7760, lng: 29.9520 },
        'section 4': { lat: -27.7740, lng: 29.9540 },
        'section 5': { lat: -27.7720, lng: 29.9560 },
        'section 6': { lat: -27.7700, lng: 29.9580 },
        'section 7': { lat: -27.7680, lng: 29.9600 },
    };
    const centre = centres[s];
    return centre
        ? new google.maps.LatLng(centre.lat, centre.lng)
        : new google.maps.LatLng(-27.7833, 29.9500);
}

// ── Utility ───────────────────────────────────────────────
function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}
