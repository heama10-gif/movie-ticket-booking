// Frontend logic: talks to the Java backend through /api/* endpoints.

const state = {
  info: null,
  showId: 0,
  booked: new Set(),
  selected: new Set(),
};

const $ = (id) => document.getElementById(id);

function priceOf(seat) {
  const row = seat[0];
  if (row <= "B") return 150;
  if (row <= "F") return 200;
  return 250;
}

function rupees(n) {
  return "\u20B9" + n;
}

function form(data) {
  return new URLSearchParams(data).toString();
}

async function api(path, options) {
  const res = await fetch(path, options);
  return res.json();
}

// ---------- Setup ----------
async function init() {
  state.info = await api("/api/info");
  $("movieTitle").textContent = state.info.movie;
  $("movieInfo").textContent = state.info.info;
  document.title = state.info.movie + " | Book tickets";
  const p = state.info.prices;
  $("priceNote").textContent =
    "Front rows A\u2013B " + rupees(p.front) +
    ", middle rows C\u2013F " + rupees(p.middle) +
    ", back rows G\u2013H " + rupees(p.premium) + " per seat";

  renderShows();
  await loadSeats();
}

function renderShows() {
  const box = $("shows");
  box.innerHTML = "";
  state.info.shows.forEach((s) => {
    const b = document.createElement("button");
    b.className = "show-btn";
    b.type = "button";
    b.setAttribute("role", "tab");
    b.setAttribute("aria-selected", s.id === state.showId);
    b.textContent = s.time;
    b.addEventListener("click", async () => {
      state.showId = s.id;
      state.selected.clear();
      renderShows();
      await loadSeats();
    });
    box.appendChild(b);
  });
}

async function loadSeats() {
  const data = await api("/api/seats?showId=" + state.showId);
  state.booked = new Set(data.booked || []);
  renderSeats();
  updateSummary();
}

function renderSeats() {
  const grid = $("seatGrid");
  grid.innerHTML = "";
  const { rows, cols } = state.info;

  for (const letter of rows) {
    const rowEl = document.createElement("div");
    rowEl.className = "seat-row";

    const label = document.createElement("span");
    label.className = "row-label";
    label.textContent = letter;
    rowEl.appendChild(label);

    for (let n = 1; n <= cols; n++) {
      const id = letter + n;
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "seat";
      if (n === Math.floor(cols / 2)) btn.classList.add("aisle"); // walkway in the middle
      btn.textContent = n;
      btn.setAttribute("aria-label", "Seat " + id + ", " + rupees(priceOf(id)));

      if (state.booked.has(id)) {
        btn.disabled = true;
        btn.setAttribute("aria-label", "Seat " + id + ", already booked");
      } else {
        if (state.selected.has(id)) btn.classList.add("selected");
        btn.setAttribute("aria-pressed", state.selected.has(id));
        btn.addEventListener("click", () => toggleSeat(id));
      }
      rowEl.appendChild(btn);
    }
    grid.appendChild(rowEl);
  }
}

function toggleSeat(id) {
  $("formError").textContent = "";
  if (state.selected.has(id)) {
    state.selected.delete(id);
  } else {
    if (state.selected.size >= state.info.maxSeats) {
      $("formError").textContent = "You can pick up to " + state.info.maxSeats + " seats at a time.";
      return;
    }
    state.selected.add(id);
  }
  renderSeats();
  updateSummary();
}

function updateSummary() {
  const seats = [...state.selected].sort();
  const total = seats.reduce((sum, s) => sum + priceOf(s), 0);
  const show = state.info.shows.find((s) => s.id === state.showId);
  $("sumShow").textContent = show ? show.time : "\u2014";
  $("sumSeats").textContent = seats.length ? seats.join(", ") : "None selected";
  $("sumTotal").textContent = rupees(total);
  $("bookBtn").disabled = seats.length === 0;
}

// ---------- Booking ----------
$("bookBtn").addEventListener("click", async () => {
  const name = $("name").value.trim();
  const phone = $("phone").value.trim();
  const err = $("formError");
  err.textContent = "";

  if (name.length < 2) { err.textContent = "Enter your full name."; return; }
  if (!/^\d{10}$/.test(phone)) { err.textContent = "Enter a 10-digit phone number."; return; }

  $("bookBtn").disabled = true;
  try {
    const result = await api("/api/book", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form({
        showId: state.showId,
        seats: [...state.selected].join(","),
        name,
        phone,
      }),
    });

    if (!result.ok) {
      err.textContent = result.error;
      state.selected.clear();      // seat may have been taken; refresh the map
      await loadSeats();
      return;
    }

    showTicket(result.booking);
    state.selected.clear();
    await loadSeats();
  } catch (e) {
    err.textContent = "Could not reach the server. Is the Java program running?";
  } finally {
    updateSummary();
  }
});

function showTicket(b) {
  $("tMovie").textContent = b.movie;
  $("tTime").textContent = b.time;
  $("tSeats").textContent = b.seats.join(", ");
  $("tName").textContent = b.name;
  $("tTotal").textContent = rupees(b.total);
  $("tId").textContent = b.id;
  $("tDate").textContent = b.createdAt;
  $("ticketDialog").showModal();
}

$("closeTicket").addEventListener("click", () => $("ticketDialog").close());

// ---------- Find / cancel ----------
function lookupValues() {
  return { id: $("lkId").value.trim(), phone: $("lkPhone").value.trim() };
}

$("lkBtn").addEventListener("click", async () => {
  const { id, phone } = lookupValues();
  const msg = $("lkMsg");
  if (!id || !phone) { msg.textContent = "Enter both the ticket ID and phone number."; return; }
  const r = await api("/api/lookup?" + form({ id, phone }));
  if (!r.ok) { msg.textContent = r.error; return; }
  const b = r.booking;
  msg.textContent =
    b.movie + " at " + b.time + "\nSeats: " + b.seats.join(", ") +
    "\nTotal: " + rupees(b.total) + "\nBooked on " + b.createdAt;
});

$("cancelBtn").addEventListener("click", async () => {
  const { id, phone } = lookupValues();
  const msg = $("lkMsg");
  if (!id || !phone) { msg.textContent = "Enter both the ticket ID and phone number."; return; }
  if (!confirm("Cancel booking " + id.toUpperCase() + "? This cannot be undone.")) return;
  const r = await api("/api/cancel", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form({ id, phone }),
  });
  msg.textContent = r.ok ? "Booking " + id.toUpperCase() + " cancelled. Seats are free again." : r.error;
  if (r.ok) await loadSeats();
});

init().catch(() => {
  $("movieTitle").textContent = "Server not reachable";
  $("movieInfo").textContent = "Start the Java program, then refresh this page.";
});