import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Movie Ticket Booking System (single screen)
 * Backend: plain Java (JDK built-in HttpServer, no Maven / no libraries needed)
 * Frontend: HTML + CSS + JavaScript in the "web" folder
 *
 * Run:   javac MovieBookingServer.java
 *        java MovieBookingServer
 * Open:  http://localhost:8080
 */
public class MovieBookingServer {

    // ---------- Theatre configuration (single screen) ----------
    static final int PORT = 8080;
    static final String MOVIE_TITLE = "Skyline Runner";
    static final String MOVIE_INFO = "Sci-fi thriller \u2022 2h 18m \u2022 UA";
    static final String[] SHOW_TIMES = {"10:00 AM", "02:00 PM", "06:30 PM", "09:30 PM"};
    static final String ROW_LETTERS = "ABCDEFGH";   // 8 rows
    static final int SEATS_PER_ROW = 10;            // 10 seats per row
    static final int MAX_SEATS_PER_BOOKING = 6;
    static final Path WEB_DIR = Paths.get("web");
    static final Path DATA_FILE = Paths.get("bookings.csv");

    // ---------- Data model ----------
    static class Booking {
        String id, name, phone, createdAt;
        int showId, total;
        List<String> seats = new ArrayList<>();
    }

    // showId -> (seat -> bookingId)
    static final Map<Integer, Map<String, String>> seatMap = new HashMap<>();
    // bookingId -> booking
    static final Map<String, Booking> bookings = new LinkedHashMap<>();
    static int nextTicketNo = 1001;

    // ---------- Pricing ----------
    static int priceOf(String seat) {
        char row = seat.charAt(0);
        if (row <= 'B') return 150;   // front rows
        if (row <= 'F') return 200;   // middle rows
        return 250;                   // back / premium rows
    }

    static boolean validSeat(String seat) {
        if (seat == null || seat.length() < 2) return false;
        if (ROW_LETTERS.indexOf(seat.charAt(0)) < 0) return false;
        try {
            int n = Integer.parseInt(seat.substring(1));
            return n >= 1 && n <= SEATS_PER_ROW;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---------- Booking logic (synchronized => no double booking) ----------
    static synchronized String book(int showId, String name, String phone, List<String> seats) throws Exception {
        if (showId < 0 || showId >= SHOW_TIMES.length) throw new Exception("Invalid show.");
        name = name == null ? "" : name.trim().replace("|", " ");
        phone = phone == null ? "" : phone.trim();
        if (name.length() < 2) throw new Exception("Please enter your name.");
        if (!Pattern.matches("\\d{10}", phone)) throw new Exception("Phone number must be 10 digits.");
        if (seats.isEmpty()) throw new Exception("Select at least one seat.");
        if (seats.size() > MAX_SEATS_PER_BOOKING)
            throw new Exception("You can book at most " + MAX_SEATS_PER_BOOKING + " seats at once.");

        Map<String, String> taken = seatMap.computeIfAbsent(showId, k -> new HashMap<>());
        Set<String> unique = new LinkedHashSet<>(seats);
        for (String s : unique) {
            if (!validSeat(s)) throw new Exception("Invalid seat: " + s);
            if (taken.containsKey(s)) throw new Exception("Seat " + s + " was just booked by someone else.");
        }

        Booking b = new Booking();
        b.id = "TKT" + (nextTicketNo++);
        b.name = name;
        b.phone = phone;
        b.showId = showId;
        b.seats.addAll(unique);
        b.total = unique.stream().mapToInt(MovieBookingServer::priceOf).sum();
        b.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));

        for (String s : unique) taken.put(s, b.id);
        bookings.put(b.id, b);
        save();
        return bookingJson(b);
    }

    static synchronized void cancel(String id, String phone) throws Exception {
        Booking b = bookings.get(id == null ? "" : id.trim().toUpperCase());
        if (b == null) throw new Exception("Booking not found.");
        if (!b.phone.equals(phone == null ? "" : phone.trim()))
            throw new Exception("Phone number does not match this booking.");
        Map<String, String> taken = seatMap.get(b.showId);
        if (taken != null) for (String s : b.seats) taken.remove(s);
        bookings.remove(b.id);
        save();
    }

    // ---------- Persistence (bookings.csv) ----------
    static synchronized void save() {
        List<String> lines = new ArrayList<>();
        for (Booking b : bookings.values()) {
            lines.add(String.join("|", b.id, b.name, b.phone, String.valueOf(b.showId),
                    String.join(",", b.seats), String.valueOf(b.total), b.createdAt));
        }
        try {
            Files.write(DATA_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Could not save bookings: " + e.getMessage());
        }
    }

    static void load() {
        if (!Files.exists(DATA_FILE)) return;
        try {
            for (String line : Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|", -1);
                if (p.length < 7) continue;
                Booking b = new Booking();
                b.id = p[0]; b.name = p[1]; b.phone = p[2];
                b.showId = Integer.parseInt(p[3]);
                b.seats.addAll(Arrays.asList(p[4].split(",")));
                b.total = Integer.parseInt(p[5]);
                b.createdAt = p[6];
                bookings.put(b.id, b);
                Map<String, String> taken = seatMap.computeIfAbsent(b.showId, k -> new HashMap<>());
                for (String s : b.seats) taken.put(s, b.id);
                nextTicketNo = Math.max(nextTicketNo, Integer.parseInt(b.id.substring(3)) + 1);
            }
        } catch (Exception e) {
            System.err.println("Could not load bookings: " + e.getMessage());
        }
    }

    // ---------- JSON helpers (hand-written, no library) ----------
    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String bookingJson(Booking b) {
        StringBuilder seats = new StringBuilder();
        for (String s : b.seats) seats.append(seats.length() > 0 ? "," : "").append("\"").append(s).append("\"");
        return "{\"id\":\"" + b.id + "\",\"name\":\"" + esc(b.name) + "\",\"movie\":\"" + esc(MOVIE_TITLE)
                + "\",\"time\":\"" + SHOW_TIMES[b.showId] + "\",\"seats\":[" + seats + "],\"total\":" + b.total
                + ",\"createdAt\":\"" + b.createdAt + "\"}";
    }

    static String error(String msg) {
        return "{\"ok\":false,\"error\":\"" + esc(msg) + "\"}";
    }

    // ---------- HTTP plumbing ----------
    static Map<String, String> parseForm(String s) throws UnsupportedEncodingException {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            String[] kv = pair.split("=", 2);
            m.put(URLDecoder.decode(kv[0], "UTF-8"), kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "");
        }
        return m;
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    static void json(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    // ---------- API handlers ----------
    static void handleInfo(HttpExchange ex) throws IOException {
        StringBuilder times = new StringBuilder();
        for (int i = 0; i < SHOW_TIMES.length; i++)
            times.append(i > 0 ? "," : "").append("{\"id\":").append(i).append(",\"time\":\"").append(SHOW_TIMES[i]).append("\"}");
        json(ex, 200, "{\"movie\":\"" + esc(MOVIE_TITLE) + "\",\"info\":\"" + esc(MOVIE_INFO) + "\",\"rows\":\""
                + ROW_LETTERS + "\",\"cols\":" + SEATS_PER_ROW + ",\"maxSeats\":" + MAX_SEATS_PER_BOOKING
                + ",\"shows\":[" + times + "],\"prices\":{\"front\":150,\"middle\":200,\"premium\":250}}");
    }

    static void handleSeats(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = parseForm(ex.getRequestURI().getRawQuery());
            int showId = Integer.parseInt(q.getOrDefault("showId", "-1"));
            if (showId < 0 || showId >= SHOW_TIMES.length) { json(ex, 400, error("Invalid show.")); return; }
            StringBuilder sb = new StringBuilder();
            synchronized (MovieBookingServer.class) {
                for (String s : seatMap.getOrDefault(showId, Collections.emptyMap()).keySet())
                    sb.append(sb.length() > 0 ? "," : "").append("\"").append(s).append("\"");
            }
            json(ex, 200, "{\"booked\":[" + sb + "]}");
        } catch (Exception e) {
            json(ex, 400, error("Bad request."));
        }
    }

    static void handleBook(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, error("Use POST.")); return; }
        try {
            Map<String, String> f = parseForm(readBody(ex));
            List<String> seats = new ArrayList<>();
            for (String s : f.getOrDefault("seats", "").split(",")) if (!s.isBlank()) seats.add(s.trim().toUpperCase());
            String booking = book(Integer.parseInt(f.getOrDefault("showId", "-1")),
                    f.get("name"), f.get("phone"), seats);
            json(ex, 200, "{\"ok\":true,\"booking\":" + booking + "}");
        } catch (NumberFormatException e) {
            json(ex, 400, error("Invalid request."));
        } catch (Exception e) {
            json(ex, 409, error(e.getMessage()));
        }
    }

    static void handleLookup(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = parseForm(ex.getRequestURI().getRawQuery());
            String id = q.getOrDefault("id", "").trim().toUpperCase();
            String phone = q.getOrDefault("phone", "").trim();
            Booking b;
            synchronized (MovieBookingServer.class) { b = bookings.get(id); }
            if (b == null || !b.phone.equals(phone)) { json(ex, 404, error("No booking found for that ticket ID and phone number.")); return; }
            json(ex, 200, "{\"ok\":true,\"booking\":" + bookingJson(b) + "}");
        } catch (Exception e) {
            json(ex, 400, error("Bad request."));
        }
    }

    static void handleCancel(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, error("Use POST.")); return; }
        try {
            Map<String, String> f = parseForm(readBody(ex));
            cancel(f.get("id"), f.get("phone"));
            json(ex, 200, "{\"ok\":true}");
        } catch (Exception e) {
            json(ex, 400, error(e.getMessage()));
        }
    }

    // ---------- Static files (index.html, style.css, app.js) ----------
    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = WEB_DIR.resolve(path.substring(1)).normalize();
        if (!file.startsWith(WEB_DIR) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "404 Not Found".getBytes());
            return;
        }
        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : "application/octet-stream";
        send(ex, 200, type, Files.readAllBytes(file));
    }

    public static void main(String[] args) throws IOException {
        load();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/info", MovieBookingServer::handleInfo);
        server.createContext("/api/seats", MovieBookingServer::handleSeats);
        server.createContext("/api/book", MovieBookingServer::handleBook);
        server.createContext("/api/lookup", MovieBookingServer::handleLookup);
        server.createContext("/api/cancel", MovieBookingServer::handleCancel);
        server.createContext("/", MovieBookingServer::handleStatic);
        server.start();
        System.out.println("Movie booking server running at http://localhost:" + PORT);
    }
}