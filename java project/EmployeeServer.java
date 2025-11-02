import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.net.URI;

public class EmployeeServer {
    static class Employee {
        int id;
        String name;
        String department;
        LocalDate joiningDate;
        Employee(int id, String name, String department, LocalDate joiningDate){
            this.id = id; this.name = name; this.department = department; this.joiningDate = joiningDate;
        }
    }
    static class AttendanceRecord {
        int employeeId;
        LocalDate date;
        AttendanceRecord(int employeeId, LocalDate date){ this.employeeId = employeeId; this.date = date; }
    }

    private static final List<Employee> employees = Collections.synchronizedList(new ArrayList<>());
    private static final List<AttendanceRecord> attendance = Collections.synchronizedList(new ArrayList<>());
    private static int nextId = 1;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public static void main(String[] args) throws Exception {
        int port = 8000;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", EmployeeServer::handleStatic);
        server.createContext("/api/employees", EmployeeServer::handleEmployees);
        server.createContext("/api/attendance", EmployeeServer::handleAttendance);
        server.createContext("/api/report", EmployeeServer::handleReport);
        server.setExecutor(Executors.newCachedThreadPool());
        System.out.println("Server started at http://localhost:" + port);
        server.start();
    }

    private static void handleStatic(HttpExchange ex) throws IOException {
        try {
            addCors(ex);
            URI uri = ex.getRequestURI();
            String path = uri.getPath();
            if (path.equals("/")) path = "/index.html";
            File file = new File("." + path).getCanonicalFile();
            if (!file.getPath().startsWith(new File(".").getCanonicalPath())) {
                sendResponse(ex, 403, "Forbidden");
                return;
            }
            if (!file.isFile()) {
                sendResponse(ex, 404, "Not Found");
                return;
            }
            String mime = guessMime(file.getName());
            ex.getResponseHeaders().set("Content-Type", mime + "; charset=utf-8");
            byte[] bytes = readAllBytes(file);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(ex, 500, "Server error");
        }
    }

    private static void handleEmployees(HttpExchange ex) throws IOException {
        try {
            addCors(ex);
            String method = ex.getRequestMethod();
            if (method.equalsIgnoreCase("GET")) {
                StringBuilder sb = new StringBuilder();
                sb.append("[");
                synchronized (employees) {
                    for (int i=0;i<employees.size();i++) {
                        Employee e = employees.get(i);
                        sb.append(employeeToJson(e));
                        if (i < employees.size()-1) sb.append(",");
                    }
                }
                sb.append("]");
                sendJson(ex, 200, sb.toString());
            } else if (method.equalsIgnoreCase("POST")) {
                String body = readRequestBody(ex);
                Map<String,String> map = parseJson(body);
                String name = map.getOrDefault("name", "").trim();
                String dept = map.getOrDefault("department", "").trim();
                String dateStr = map.getOrDefault("joiningDate", "").trim();
                if (name.isEmpty() || dept.isEmpty() || dateStr.isEmpty()) {
                    sendJson(ex, 400, "{\"error\":\"Missing fields\"}"); return;
                }
                LocalDate d;
                try { d = LocalDate.parse(dateStr, FMT); } catch (Exception ee) {
                    sendJson(ex, 400, "{\"error\":\"Invalid date format, use dd-MM-yyyy\"}"); return;
                }
                Employee e;
                synchronized (employees) {
                    e = new Employee(nextId++, name, dept, d);
                    employees.add(e);
                }
                sendJson(ex, 201, employeeToJson(e));
            } else if (method.equalsIgnoreCase("DELETE")) {
                String q = ex.getRequestURI().getQuery();
                Map<String,String> qp = parseQuery(q);
                String idStr = qp.get("id");
                if (idStr == null) { sendJson(ex, 400, "{\"error\":\"Missing id\"}"); return; }
                int id = Integer.parseInt(idStr);
                boolean removed = false;
                synchronized (employees) {
                    Iterator<Employee> it = employees.iterator();
                    while (it.hasNext()) {
                        if (it.next().id == id) { it.remove(); removed = true; break; }
                    }
                }
                if (removed) sendJson(ex, 200, "{\"status\":\"deleted\"}");
                else sendJson(ex, 404, "{\"error\":\"not found\"}");
            } else {
                sendResponse(ex, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(ex, 500, "Server error");
        }
    }

    private static void handleAttendance(HttpExchange ex) throws IOException {
        try {
            addCors(ex);
            String method = ex.getRequestMethod();
            if (method.equalsIgnoreCase("POST")) {
                String body = readRequestBody(ex);
                Map<String,String> map = parseJson(body);
                String idStr = map.getOrDefault("employeeId", "").trim();
                String dateStr = map.getOrDefault("date", "").trim();
                if (idStr.isEmpty() || dateStr.isEmpty()) {
                    sendJson(ex, 400, "{\"error\":\"Missing fields\"}"); return;
                }
                int id = Integer.parseInt(idStr);
                LocalDate d;
                try { d = LocalDate.parse(dateStr, FMT); } catch (Exception ee) {
                    sendJson(ex, 400, "{\"error\":\"Invalid date format, use dd-MM-yyyy\"}"); return;
                }
                boolean exists = false;
                synchronized (employees) {
                    for (Employee e: employees) if (e.id == id) { exists = true; break; }
                }
                if (!exists) { sendJson(ex, 404, "{\"error\":\"Employee not found\"}"); return; }
                synchronized (attendance) {
                    attendance.add(new AttendanceRecord(id, d));
                }
                sendJson(ex, 201, "{\"status\":\"attendance recorded\"}");
            } else if (method.equalsIgnoreCase("GET")) {
                String q = ex.getRequestURI().getQuery();
                Map<String,String> qp = parseQuery(q);
                String idStr = qp.get("employeeId");
                List<String> list = new ArrayList<>();
                synchronized (attendance) {
                    for (AttendanceRecord ar: attendance) {
                        if (idStr == null || Integer.toString(ar.employeeId).equals(idStr)) {
                            list.add("{\"employeeId\":" + ar.employeeId + ",\"date\":\"" + ar.date.format(FMT) + "\"}");
                        }
                    }
                }
                StringBuilder sb = new StringBuilder();
                sb.append("[").append(String.join(",", list)).append("]");
                sendJson(ex, 200, sb.toString());
            } else {
                sendResponse(ex, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(ex, 500, "Server error");
        }
    }

    private static void handleReport(HttpExchange ex) throws IOException {
        try {
            addCors(ex);
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { sendResponse(ex, 405, "Method Not Allowed"); return; }
            Map<String,Integer> counts = new HashMap<>();
            synchronized (employees) {
                for (Employee e : employees) counts.put(e.department, counts.getOrDefault(e.department,0)+1);
            }
            Map<Integer,Integer> attendanceCount = new HashMap<>();
            synchronized (attendance) {
                for (AttendanceRecord ar: attendance) attendanceCount.put(ar.employeeId, attendanceCount.getOrDefault(ar.employeeId,0)+1);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"departmentCounts\":{");
            int di=0;
            for (Map.Entry<String,Integer> en : counts.entrySet()) {
                sb.append(quote(en.getKey())).append(":").append(en.getValue());
                if (++di < counts.size()) sb.append(",");
            }
            sb.append("},");
            sb.append("\"attendanceCounts\":{");
            int ai=0;
            for (Map.Entry<Integer,Integer> en : attendanceCount.entrySet()) {
                sb.append("\"").append(en.getKey()).append("\":").append(en.getValue());
                if (++ai < attendanceCount.size()) sb.append(",");
            }
            sb.append("}");
            sb.append("}");
            sendJson(ex, 200, sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(ex, 500, "Server error");
        }
    }

    // utilities
    private static void addCors(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.add("Access-Control-Allow-Origin", "*");
        h.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        h.add("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            try { ex.sendResponseHeaders(204, -1); } catch (IOException ignored) {}
        }
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendResponse(HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String employeeToJson(Employee e) {
        return "{" + quote("id") + ":" + e.id + "," + quote("name") + ":" + quote(e.name) + "," + quote("department") + ":" + quote(e.department) + "," + quote("joiningDate") + ":" + quote(e.joiningDate.format(FMT)) + "}";
    }

    private static String quote(String s) { return '"' + s.replace("\"","\\\"") + '"'; }

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int)file.length()];
            int r=0; while (r < buf.length) { int n = fis.read(buf, r, buf.length - r); if (n<0) break; r += n; }
            return buf;
        }
    }

    private static String guessMime(String name) {
        name = name.toLowerCase();
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        return "text/plain";
    }

    private static String readRequestBody(HttpExchange ex) throws IOException {
        InputStream is = ex.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n; while ((n=is.read(buf))>0) baos.write(buf,0,n);
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String,String> parseJson(String s) {
        Map<String,String> map = new HashMap<>();
        if (s == null) return map;
        s = s.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length()-1);
        if (s.trim().isEmpty()) return map;
        String[] parts = s.split(",");
        for (String p : parts) {
            int idx = p.indexOf(":");
            if (idx < 0) continue;
            String key = p.substring(0, idx).trim();
            String val = p.substring(idx+1).trim();
            key = stripQuotes(key); val = stripQuotes(val);
            map.put(key, val);
        }
        return map;
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length()-1);
        if (s.startsWith("'") && s.endsWith("'")) s = s.substring(1, s.length()-1);
        return s;
    }

    private static Map<String,String> parseQuery(String q) {
        Map<String,String> map = new HashMap<>();
        if (q == null || q.isEmpty()) return map;
        String[] parts = q.split("&");
        for (String p : parts) {
            String[] kv = p.split("=",2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }
}
