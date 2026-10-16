import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class LinkdroidLauncher_nokeyboard {

    private static final String SCRIPT_DIR = new File(".").getAbsolutePath();
    private static final File ADB = new File(SCRIPT_DIR, "scrcpy\\adb.exe");
    private static final File SCRCPY = new File(SCRIPT_DIR, "scrcpy\\scrcpy.exe");
    private static final File DEVICES_FILE = new File(SCRIPT_DIR, "devices.json");
    private static final File SETTINGS_FILE = new File(SCRIPT_DIR, "settings.json");
    private static final File LOG_FILE = new File(SCRIPT_DIR, "linkdroid.log");
    private static String TAILSCALE_IP = "";

    private static final String LINKDROID_SERVICE_TYPE = "_linkdroid._tcp.local.";
    private static final int BEACON_PORT = 9876;
    private static final int SCAN_DURATION_SECONDS = 10;
    private static final int PORT_SCAN_THREADS = 200;
    private static final int PORT_SCAN_START = 37000;
    private static final int PORT_SCAN_END = 44000;
    private static final int TAILSCALE_CLI_TIMEOUT_SECONDS = 15;
    private static final int HEARTBEAT_INTERVAL_SECONDS = 3;
    private static final int MAX_HEARTBEAT_FAILURES = 3;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int RECONNECT_ATTEMPT_TIMEOUT_SECONDS = 10;
    private static final int SCREEN_TIMEOUT_DISABLE_MS = 86400000;
    private static final int SCREEN_TIMEOUT_RESTORE_DELAY_MS = 2000;
    private static final int[] STANDARD_ADB_PORTS = {5555, 5556, 5558};
    private static final int BEACON_PROBE_TIMEOUT_MS = 5000;
    private static final Scanner SCANNER = new Scanner(System.in);

    private enum SessionState {
        IDLE, DISCOVERING, CONNECTING, CONNECTED, RECONNECTING, FAILED
    }

    private static class SessionContext {
        volatile SessionState state = SessionState.IDLE;
        volatile DeviceInfo selectedDevice;
        volatile Process scrcpyProcess;
        volatile Thread heartbeatThread;
        volatile AtomicInteger reconnectAttempt = new AtomicInteger(0);
        volatile long reconnectStartTime = 0;
        volatile boolean stopRequested = false;
        final Object stateLock = new Object();

        void transitionTo(SessionState newState) {
            synchronized (stateLock) {
                SessionState oldState = state;
                state = newState;
                log("STATE: " + oldState + " -> " + newState);
            }
        }
    }

    private static final SessionContext SESSION = new SessionContext();
    private static final List<DeviceInfo> foundDevices = Collections.synchronizedList(new ArrayList<>());

    static String safeReadLine() {
        try {
            if (SCANNER.hasNextLine()) {
                return SCANNER.nextLine();
            }
            return "";
        } catch (NoSuchElementException e) {
            return "";
        }
    }

    static void log(String message) {
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String line = "[" + timestamp + "] " + message;
        System.out.println(line);
        try {
            Files.write(LOG_FILE.toPath(), Collections.singletonList(line), StandardCharsets.UTF_8,
                    LOG_FILE.exists() ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
        } catch (IOException e) {
            // ignore log write errors
        }
    }

    static class DeviceInfo {
        String name;
        String ip;
        int port;
        String wifiIp;
        String tailscaleIp;
        String androidVersion;
        String deviceModel;
        int beaconVersion;
        boolean isUsb;
        List<String> allIps;
        String callState;

        DeviceInfo(String name, String ip, int port) {
            this(name, ip, port, "", "", "", "", 1, false, new ArrayList<>(), "IDLE");
        }

        DeviceInfo(String name, String ip, int port, String wifiIp, String tailscaleIp,
                   String androidVersion, String deviceModel, int beaconVersion) {
            this(name, ip, port, wifiIp, tailscaleIp, androidVersion, deviceModel, beaconVersion, false, new ArrayList<>(), "IDLE");
        }

        DeviceInfo(String name, String ip, int port, String wifiIp, String tailscaleIp,
                   String androidVersion, String deviceModel, int beaconVersion, boolean isUsb) {
            this(name, ip, port, wifiIp, tailscaleIp, androidVersion, deviceModel, beaconVersion, isUsb, new ArrayList<>(), "IDLE");
        }

        DeviceInfo(String name, String ip, int port, String wifiIp, String tailscaleIp,
                   String androidVersion, String deviceModel, int beaconVersion, boolean isUsb, List<String> allIps) {
            this(name, ip, port, wifiIp, tailscaleIp, androidVersion, deviceModel, beaconVersion, isUsb, allIps, "IDLE");
        }

        DeviceInfo(String name, String ip, int port, String wifiIp, String tailscaleIp,
                   String androidVersion, String deviceModel, int beaconVersion, boolean isUsb, List<String> allIps, String callState) {
            this.name = name;
            this.ip = ip;
            this.port = port;
            this.wifiIp = wifiIp;
            this.tailscaleIp = tailscaleIp;
            this.androidVersion = androidVersion;
            this.deviceModel = deviceModel;
            this.beaconVersion = beaconVersion;
            this.isUsb = isUsb;
            this.allIps = allIps != null ? allIps : new ArrayList<>();
            this.callState = callState != null ? callState : "IDLE";
        }

        String target() {
            if (isUsb) {
                return ip;
            }
            return formatIpPort(ip, port);
        }

        String displayName() {
            String suffix = "";
            if (tailscaleIp != null && !tailscaleIp.isEmpty() && !tailscaleIp.equals(ip)) {
                suffix = " [Tailscale: " + tailscaleIp + "]";
            }
            if (wifiIp != null && !wifiIp.isEmpty() && !wifiIp.equals(ip)) {
                suffix = " [WiFi: " + wifiIp + "]" + suffix;
            }
            if (isUsb) {
                suffix = " [USB]" + suffix;
            }
            if (callState != null && !callState.isEmpty() && !callState.equals("IDLE")) {
                suffix = " [Call: " + callState + "]" + suffix;
            }
            return name + " (" + target() + ")" + suffix;
        }

        static String formatIpPort(String ip, int port) {
            if (ip != null && ip.contains(":")) {
                return "[" + ip + "]:" + port;
            }
            return ip + ":" + port;
        }
    }

    static void mergeDeviceInfo(DeviceInfo target, DeviceInfo source) {
        if (target == null || source == null) return;
        if (target.name.isEmpty() && !source.name.isEmpty()) target.name = source.name;
        if (target.ip.isEmpty() && !source.ip.isEmpty()) target.ip = source.ip;
        if (target.port <= 0 && source.port > 0) target.port = source.port;
        if (target.wifiIp.isEmpty() && !source.wifiIp.isEmpty()) target.wifiIp = source.wifiIp;
        if (target.tailscaleIp.isEmpty() && !source.tailscaleIp.isEmpty()) target.tailscaleIp = source.tailscaleIp;
        if (target.androidVersion.isEmpty() && !source.androidVersion.isEmpty()) target.androidVersion = source.androidVersion;
        if (target.deviceModel.isEmpty() && !source.deviceModel.isEmpty()) target.deviceModel = source.deviceModel;
        if (source.allIps != null) {
            for (String ip : source.allIps) {
                if (!ip.isEmpty() && !target.allIps.contains(ip)) {
                    target.allIps.add(ip);
                }
            }
        }
        if (target.beaconVersion < source.beaconVersion) target.beaconVersion = source.beaconVersion;
    }

    static class NetworkAdapter {
        String name;
        String displayName;
        List<String> allIps;
        String primaryIp;
        int subnetPrefix;
        String adapterType;
        boolean isTailscale;
        boolean isUp;
        NetworkInterface networkInterface;

        NetworkAdapter(String name, String displayName, NetworkInterface ni, boolean isUp,
                       String primaryIp, int subnetPrefix, String adapterType, boolean isTailscale) {
            this.name = name;
            this.displayName = displayName;
            this.networkInterface = ni;
            this.isUp = isUp;
            this.allIps = new ArrayList<>();
            this.primaryIp = primaryIp;
            this.subnetPrefix = subnetPrefix;
            this.adapterType = adapterType;
            this.isTailscale = isTailscale;
        }

        void addIp(String ip) {
            if (!allIps.contains(ip)) {
                allIps.add(ip);
            }
        }

        List<String> getProbeIps() {
            List<String> probeIps = new ArrayList<>();
            if (primaryIp != null && !primaryIp.isEmpty() && subnetPrefix > 0 && subnetPrefix < 32) {
                try {
                    List<String> subnetIps = generateSubnetIps(primaryIp, subnetPrefix);
                    int maxProbe = 30;
                    int count = 0;
                    for (String ip : subnetIps) {
                        if (!probeIps.contains(ip)) {
                            probeIps.add(ip);
                            count++;
                            if (count >= maxProbe) break;
                        }
                    }
                } catch (Exception e) {
                    log("NETWORK_ADAPTER: failed to generate subnet for " + primaryIp + "/" + subnetPrefix + ": " + e.getMessage());
                }
            }
            return probeIps;
        }

        private List<String> generateSubnetIps(String ipStr, int prefix) {
            List<String> ips = new ArrayList<>();
            String[] parts = ipStr.split("\\.");
            if (parts.length != 4) return ips;
            int ip = (Integer.parseInt(parts[0]) << 24) | (Integer.parseInt(parts[1]) << 16) |
                     (Integer.parseInt(parts[2]) << 8) | Integer.parseInt(parts[3]);
            int mask = prefix == 32 ? -1 : (~0 << (32 - prefix));
            int network = ip & mask;
            int broadcast = network | ~mask;
            int start = network + 1;
            int end = broadcast - 1;
            int maxScan = 254;
            int count = 0;
            for (int i = start; i <= end && count < maxScan; i++) {
                if (i == ip) continue;
                int octet1 = (i >> 24) & 0xff;
                int octet2 = (i >> 16) & 0xff;
                int octet3 = (i >> 8) & 0xff;
                int octet4 = i & 0xff;
                ips.add(octet1 + "." + octet2 + "." + octet3 + "." + octet4);
                count++;
            }
            return ips;
        }
    }

    static class AdbListener implements ServiceListener {
        @Override
        public void serviceAdded(ServiceEvent event) {
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            String removedName = event.getName();
            foundDevices.removeIf(d -> d.name.equals(removedName) || d.ip.equals(removedName));
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            ServiceInfo info = event.getInfo();
            InetAddress[] addresses = info.getInetAddresses();
            int port = info.getPort();
            String name = event.getName();

            Map<String, String> attributes = new HashMap<>();
            String textData = info.getTextString();
            if (textData != null && !textData.isEmpty()) {
                for (String entry : textData.split("\n")) {
                    int eqIdx = entry.indexOf("=");
                    if (eqIdx > 0) {
                        String key = entry.substring(0, eqIdx);
                        String value = entry.substring(eqIdx + 1);
                        attributes.put(key, value);
                    }
                }
            }

            String wifiIp = attributes.getOrDefault("wifiip", "");
            String tailscaleIp = attributes.getOrDefault("tailscaleip", "");
            String allIpsStr = attributes.getOrDefault("allips", "");
            String androidVersion = attributes.getOrDefault("androidversion", "");
            String deviceModel = attributes.getOrDefault("devicemodel", "");
            String beaconVersionStr = attributes.getOrDefault("beaconversion", "1");
            int beaconVersion = 1;
            try {
                beaconVersion = Integer.parseInt(beaconVersionStr);
            } catch (NumberFormatException e) {
                // default to 1
            }
            List<String> allIps = new ArrayList<>();
            if (!allIpsStr.isEmpty()) {
                for (String ip : allIpsStr.split(",")) {
                    if (!ip.isEmpty()) {
                        allIps.add(ip.trim());
                    }
                }
            }

            for (InetAddress addr : addresses) {
                String ip = addr.getHostAddress();
                DeviceInfo device = new DeviceInfo(name, ip, port, wifiIp, tailscaleIp,
                        androidVersion, deviceModel, beaconVersion, false, allIps);
                boolean exists = false;
                synchronized (foundDevices) {
                    for (DeviceInfo d : foundDevices) {
                        if (d.ip.equals(ip) || d.name.equals(name)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        foundDevices.add(device);
                    }
                }
            }
        }
    }

    static List<String> loadDevices() {
        List<String> devices = new ArrayList<>();
        if (DEVICES_FILE.exists()) {
            try {
                List<String> lines = Files.readAllLines(DEVICES_FILE.toPath());
                for (String line : lines) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        String ip = line.split(":")[0];
                        if (!devices.contains(ip)) {
                            devices.add(ip);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to read devices.json: " + e.getMessage());
            }
        }
        return devices;
    }

    static void saveDevice(String entry) {
        List<String> devices = loadDevices();
        String ip = entry.split(":")[0];
        if (!devices.contains(ip)) {
            devices.add(ip);
            try {
                Files.write(DEVICES_FILE.toPath(), devices, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("Failed to write devices.json: " + e.getMessage());
            }
        }
    }

    static void loadSettings() {
        if (SETTINGS_FILE.exists()) {
            try {
                String content = new String(Files.readAllBytes(SETTINGS_FILE.toPath()), StandardCharsets.UTF_8);
                for (String line : content.split("\n")) {
                    line = line.trim();
                    if (line.contains("tailscaleIp")) {
                        int start = line.indexOf(":");
                        int end = line.lastIndexOf("\"");
                        if (start > 0 && end > start) {
                            TAILSCALE_IP = line.substring(start + 1, end).trim().replace("\"", "");
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to read settings.json: " + e.getMessage());
            }
        }
    }

    static Integer resolvePortByIp(String ip, List<DeviceInfo> found) {
        for (DeviceInfo d : found) {
            if (d.ip.equals(ip)) {
                return d.port;
            }
        }
        return null;
    }

    static int runCommand(File executable, List<String> args, boolean waitForCompletion) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        if (!waitForCompletion) {
            Thread drainThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // drain
                    }
                } catch (Exception e) {
                    // ignore
                }
            }, "cmd-drain");
            drainThread.setDaemon(true);
            drainThread.start();
            process.waitFor(5, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroy();
            }
            return -1;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        return process.waitFor();
    }

    static String runCommandCaptureOutput(File executable, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        process.waitFor();
        return output.toString();
    }

    static Integer getScreenTimeout(String target) {
        try {
            String output = runCommandCaptureOutput(ADB, Arrays.asList("-s", target, "shell", "settings", "get", "system", "screen_off_timeout"));
            return Integer.parseInt(output.trim());
        } catch (Exception e) {
            return null;
        }
    }

    static void setScreenTimeout(String target, int timeoutMs) {
        try {
            runCommand(ADB, Arrays.asList("-s", target, "shell", "settings", "put", "system", "screen_off_timeout", String.valueOf(timeoutMs)), true);
        } catch (Exception e) {
            System.err.println("Failed to set screen timeout: " + e.getMessage());
        }
    }

    static String chooseResolution() {
        System.out.println();
        System.out.println("Select resolution:");
        System.out.println("  1. 720p (default)");
        System.out.println("  2. 1080p");
        System.out.println("  3. 4K");
        while (true) {
            System.out.print("Enter choice (default: 1): ");
            String choice = safeReadLine();
            if (choice.isEmpty()) {
                return "720";
            }
            switch (choice) {
                case "1": return "720";
                case "2": return "1080";
                case "3": return "2160";
                default:
                    System.out.println("Invalid selection.");
            }
        }
    }

    static boolean adbPair(String target) {
        System.out.println("Pairing with " + target + "...");
        System.out.println("Enter the 6-digit pairing code shown on your phone.");
        System.out.print("Pairing code: ");
        String code = safeReadLine();
        if (code.isEmpty()) {
            return false;
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String output = runCommandCaptureOutput(ADB, Arrays.asList("pair", target, code));
                System.out.println(output);
                if (output.toLowerCase().contains("pairing successful")) {
                    return true;
                }
                if (output.toLowerCase().contains("protocol fault") || output.toLowerCase().contains("no error")) {
                    System.out.println("Pairing attempt " + attempt + " failed transiently, retrying...");
                    Thread.sleep(1000);
                    continue;
                }
                System.out.println("Pairing failed: " + output);
                return false;
            } catch (Exception e) {
                System.out.println("Pairing attempt " + attempt + " error: " + e.getMessage());
                if (attempt < 3) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                }
            }
        }
        System.out.println("Pairing failed after 3 attempts.");
        return false;
    }

    static Integer extractPortFromAdbDevices(String ip) {
        try {
            String output = runCommandCaptureOutput(ADB, Arrays.asList("devices"));
            for (String line : output.split("\n")) {
                if (line.contains("\tdevice") || line.contains("\toffline")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 0) {
                        String device = parts[0];
                        if (device.startsWith(ip + ":")) {
                            String portStr = device.substring(ip.length() + 1);
                            try {
                                return Integer.parseInt(portStr);
                            } catch (NumberFormatException e) {
                                // ignore
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to check adb devices: " + e.getMessage());
        }
        return null;
    }

    static Integer scanPortRange(String ip) {
        System.out.println("Scanning ports " + PORT_SCAN_START + "-" + PORT_SCAN_END + " on " + ip + "...");
        ExecutorService executor = Executors.newFixedThreadPool(PORT_SCAN_THREADS);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int port = PORT_SCAN_START; port <= PORT_SCAN_END; port++) {
            final int p = port;
            futures.add(executor.submit(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(ip, p), 200);
                    return p;
                } catch (Exception e) {
                    return -1;
                }
            }));
        }

        executor.shutdown();
        Integer foundPort = null;
        long startTime = System.currentTimeMillis();
        for (Future<Integer> future : futures) {
            try {
                Integer port = future.get(2, TimeUnit.SECONDS);
                if (port > 0) {
                    foundPort = port;
                    break;
                }
            } catch (Exception e) {
                // timeout or cancelled
            }
        }
        executor.shutdownNow();

        long elapsed = System.currentTimeMillis() - startTime;
        if (foundPort != null) {
            System.out.println("Found open port: " + foundPort + " (scanned in " + elapsed + "ms)");
        } else {
            System.out.println("No open ports found in range " + PORT_SCAN_START + "-" + PORT_SCAN_END + " (" + elapsed + "ms)");
        }
        return foundPort;
    }

    static List<String> discoverTailscaleIpsViaCli() {
        List<String> ips = new ArrayList<>();
        String tailscaleCmd = null;

        try {
            Process which = Runtime.getRuntime().exec(new String[]{"where", "tailscale"});
            which.waitFor(3, TimeUnit.SECONDS);
            if (which.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(which.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        tailscaleCmd = line.trim();
                    }
                }
            }
        } catch (Exception e) {
            // try default paths
        }

        if (tailscaleCmd == null) {
            File[] fallbacks = new File[]{
                    new File("C:\\Program Files\\Tailscale\\tailscale.exe"),
                    new File("C:\\Program Files (x86)\\Tailscale\\tailscale.exe")
            };
            for (File fallback : fallbacks) {
                if (fallback.exists() && fallback.canExecute()) {
                    tailscaleCmd = fallback.getAbsolutePath();
                    break;
                }
            }
        }

        if (tailscaleCmd == null) {
            log("TAILSCALE_CLI: tailscale executable not found");
            return ips;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(tailscaleCmd, "status", "--json");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder json = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        json.append(line);
                    }
                } catch (Exception e) {
                    // ignore read errors
                }
            }, "tailscale-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished = process.waitFor(TAILSCALE_CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                log("TAILSCALE_CLI: command timed out after " + TAILSCALE_CLI_TIMEOUT_SECONDS + "s");
                process.destroy();
                readerThread.join(2000);
                return ips;
            }
            readerThread.join(2000);

            if (process.exitValue() != 0) {
                log("TAILSCALE_CLI: command failed with exit code " + process.exitValue());
                return ips;
            }

            String body = json.toString();
            if (body.isEmpty()) {
                log("TAILSCALE_CLI: empty output from tailscale status --json");
                return ips;
            }

            String localIp = extractLocalTailscaleIp(body);
            int idx = 0;
            while ((idx = body.indexOf("\"TailscaleIPs\":", idx)) != -1) {
                int arrayStart = body.indexOf('[', idx);
                int arrayEnd = body.indexOf(']', arrayStart);
                if (arrayStart != -1 && arrayEnd != -1) {
                    String addressesStr = body.substring(arrayStart + 1, arrayEnd);
                    for (String addr : addressesStr.split(",")) {
                        addr = addr.replace("\"", "").trim();
                        if (!addr.isEmpty() && !ips.contains(addr) && !addr.equals(localIp)) {
                            ips.add(addr);
                        }
                    }
                }
                idx = arrayEnd + 1;
            }
            log("TAILSCALE_CLI: found " + ips.size() + " devices, local IP: " + localIp);
        } catch (Exception e) {
            log("TAILSCALE_CLI: error: " + e.getMessage());
        }
        return ips;
    }

    static List<NetworkAdapter> discoverNetworkAdapters() {
        List<NetworkAdapter> adapters = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                boolean isUp = ni.isUp() && !ni.isLoopback();
                if (!isUp || ni.isLoopback()) {
                    continue;
                }

                String name = ni.getName();
                String displayName = ni.getDisplayName();
                String adapterType = classifyAdapter(name, displayName);
                boolean isTailscale = adapterType.equals("Tailscale");

                List<java.net.InterfaceAddress> ifaceAddresses = new ArrayList<>(ni.getInterfaceAddresses());

                if (ifaceAddresses.isEmpty()) continue;

                NetworkAdapter adapter = new NetworkAdapter(name, displayName, ni, isUp, "", 0, adapterType, isTailscale);
                String primaryIpv4 = "";
                String primaryIpv6 = "";
                int subnetPrefix = 0;

                for (java.net.InterfaceAddress ifaceAddr : ifaceAddresses) {
                    InetAddress addr = ifaceAddr.getAddress();
                    String ip = addr.getHostAddress();
                    if (ip == null) continue;
                    if (ip.startsWith("127.") || ip.equals("0.0.0.0")) continue;
                    if (ip.startsWith("fe80:") || ip.startsWith("FE80:")) continue;
                    if (addr.isLinkLocalAddress()) continue;
                    adapter.addIp(ip);
                    int prefix = ifaceAddr.getNetworkPrefixLength();
                    if (ip.contains(".")) {
                        if (primaryIpv4.isEmpty()) {
                            primaryIpv4 = ip;
                            subnetPrefix = prefix;
                        }
                    } else if (primaryIpv6.isEmpty() && ip.contains(":")) {
                        primaryIpv6 = ip;
                    }
                }

                String primaryIp = !primaryIpv4.isEmpty() ? primaryIpv4 : primaryIpv6;

                if (!adapter.allIps.isEmpty()) {
                    adapter.primaryIp = primaryIp;
                    adapter.subnetPrefix = subnetPrefix;
                    adapters.add(adapter);
                    log("NETWORK_ADAPTER: " + adapter.displayName + " (" + adapter.adapterType + ") IPs=" + String.join(", ", adapter.allIps) + " prefix=" + subnetPrefix);
                }
            }
            log("NETWORK_ADAPTER: found " + adapters.size() + " adapters");
        } catch (Exception e) {
            log("NETWORK_ADAPTER: error: " + e.getMessage());
        }
        return adapters;
    }

    static List<String> discoverLocalNetworkIps() {
        List<String> ips = new ArrayList<>();
        List<NetworkAdapter> adapters = discoverNetworkAdapters();
        for (NetworkAdapter adapter : adapters) {
            for (String ip : adapter.allIps) {
                if (!ips.contains(ip)) {
                    ips.add(ip);
                }
            }
        }
        return ips;
    }

    private static String classifyAdapter(String name, String displayName) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerDisplay = (displayName != null ? displayName : "").toLowerCase(Locale.ROOT);
        if (lowerName.contains("tailscale") || lowerName.contains("utun200") || lowerName.contains("wg0") ||
            lowerDisplay.contains("tailscale")) {
            return "Tailscale";
        }
        if (lowerName.contains("wlan") || lowerName.contains("wifi") || lowerName.contains("softap") || lowerName.contains("ap0") ||
            lowerDisplay.contains("wi-fi") || lowerDisplay.contains("wireless")) {
            return "WiFi";
        }
        if (lowerName.contains("virtual") || lowerName.contains("vmware") || lowerName.contains("vbox") || lowerName.contains("hyper-v") ||
            lowerDisplay.contains("virtual") || lowerDisplay.contains("vmware") || lowerDisplay.contains("virtualbox") || lowerDisplay.contains("hyper-v")) {
            return "Virtual";
        }
        if (lowerName.contains("eth") || lowerName.contains("enp") || lowerName.contains("enx") ||
            lowerDisplay.contains("ethernet") || lowerDisplay.contains("lan")) {
            return "Ethernet";
        }
        if (lowerName.contains("tun") || lowerName.contains("ppp") || lowerName.contains("vpn") ||
            lowerDisplay.contains("vpn")) {
            return "VPN";
        }
        return "Other";
    }

    static String extractLocalTailscaleIp(String json) {
        int idx = json.indexOf("\"Self\":");
        if (idx == -1) return "";
        int ipsIdx = json.indexOf("\"TailscaleIPs\":", idx);
        if (ipsIdx == -1) return "";
        int arrayStart = json.indexOf('[', ipsIdx);
        int arrayEnd = json.indexOf(']', arrayStart);
        if (arrayStart == -1 || arrayEnd == -1) return "";
        String addressesStr = json.substring(arrayStart + 1, arrayEnd);
        for (String addr : addressesStr.split(",")) {
            addr = addr.replace("\"", "").trim();
            if (!addr.isEmpty()) {
                return addr;
            }
        }
        return "";
    }

    static List<DeviceInfo> scanMdns(List<NetworkAdapter> adapters) {
        List<DeviceInfo> discovered = new ArrayList<>();
        List<JmDNS> jmdnsInstances = new ArrayList<>();
        List<AdbListener> listeners = new ArrayList<>();

        foundDevices.clear();

        if (adapters == null || adapters.isEmpty()) {
            adapters = discoverNetworkAdapters();
        }

        try {
            for (NetworkAdapter adapter : adapters) {
                if (adapter.primaryIp.isEmpty()) continue;
                try {
                    JmDNS jmdns = JmDNS.create(InetAddress.getByName(adapter.primaryIp));
                    AdbListener listener = new AdbListener();
                    jmdns.addServiceListener(LINKDROID_SERVICE_TYPE, listener);
                    jmdnsInstances.add(jmdns);
                    listeners.add(listener);
                    log("mDNS: bound to " + adapter.displayName + " (" + adapter.primaryIp + ")");
                } catch (Exception e) {
                    System.err.println("mDNS: failed to bind to " + adapter.displayName + " (" + adapter.primaryIp + "): " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("mDNS: error enumerating adapters: " + e.getMessage());
        }

        if (jmdnsInstances.isEmpty()) {
            try {
                JmDNS jmdns = JmDNS.create();
                AdbListener listener = new AdbListener();
                jmdns.addServiceListener(LINKDROID_SERVICE_TYPE, listener);
                jmdnsInstances.add(jmdns);
                listeners.add(listener);
                log("mDNS: fallback to auto-select");
            } catch (Exception e) {
                System.err.println("mDNS scan error: " + e.getMessage());
                return discovered;
            }
        }

        try {
            Thread.sleep(SCAN_DURATION_SECONDS * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (int i = 0; i < jmdnsInstances.size(); i++) {
            try {
                jmdnsInstances.get(i).removeServiceListener(LINKDROID_SERVICE_TYPE, listeners.get(i));
            } catch (Exception e) {
                // ignore cleanup errors
            }
        }
        for (JmDNS jmdns : jmdnsInstances) {
            try {
                jmdns.close();
            } catch (Exception e) {
                // ignore cleanup errors
            }
        }
        discovered.addAll(foundDevices);
        return discovered;
    }

    static DeviceInfo scanTcpBeacon(String ip) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, BEACON_PORT), BEACON_PROBE_TIMEOUT_MS);
            socket.setSoTimeout(30000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            String deviceName = "";
            String adbPortStr = "";
            String tailscaleIp = "";
            String wifiIp = "";
            String allIpsStr = "";
            String androidVersion = "";
            String deviceModel = "";
            String beaconVersionStr = "";
            String callState = "IDLE";
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("DEVICE_NAME=")) {
                    deviceName = line.substring("DEVICE_NAME=".length());
                } else if (line.startsWith("ADB_PORT=")) {
                    adbPortStr = line.substring("ADB_PORT=".length());
                } else if (line.startsWith("TAILSCALE_IP=")) {
                    tailscaleIp = line.substring("TAILSCALE_IP=".length());
                } else if (line.startsWith("WIFI_IP=")) {
                    wifiIp = line.substring("WIFI_IP=".length());
                } else if (line.startsWith("ALL_IPS=")) {
                    allIpsStr = line.substring("ALL_IPS=".length());
                } else if (line.startsWith("ANDROID_VERSION=")) {
                    androidVersion = line.substring("ANDROID_VERSION=".length());
                } else if (line.startsWith("DEVICE_MODEL=")) {
                    deviceModel = line.substring("DEVICE_MODEL=".length());
                } else if (line.startsWith("BEACON_VERSION=")) {
                    beaconVersionStr = line.substring("BEACON_VERSION=".length());
                } else if (line.startsWith("CALL_STATE=")) {
                    callState = line.substring("CALL_STATE=".length());
                }
            }
            if (!adbPortStr.isEmpty()) {
                int adbPort = Integer.parseInt(adbPortStr);
                if (adbPort <= 0) {
                    log("BEACON: " + ip + " returned ADB_PORT=0, will try standard ADB ports");
                    return null;
                }
                String resolvedIp = !wifiIp.isEmpty() ? wifiIp : ip;
                int beaconVersion = 1;
                try {
                    beaconVersion = Integer.parseInt(beaconVersionStr);
                } catch (NumberFormatException e) {
                    // default to 1
                }
                DeviceInfo device = new DeviceInfo(deviceName, resolvedIp, adbPort, wifiIp, tailscaleIp,
                        androidVersion, deviceModel, beaconVersion);
                device.allIps = new ArrayList<>(Arrays.asList(allIpsStr.split(",")));
                device.callState = callState;
                return device;
            }
        } catch (Exception e) {
            // no beacon on this IP
        }
        return null;
    }

    static boolean sendBeaconCommand(String ip, String command) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, BEACON_PORT), BEACON_PROBE_TIMEOUT_MS);
            socket.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream());
            writer.write(command + "\n");
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("CALL_STATE=")) {
                    String newState = line.substring("CALL_STATE=".length());
                    log("BEACON_CMD: " + ip + " call state is now " + newState);
                    return true;
                }
            }
        } catch (Exception e) {
            log("BEACON_CMD: failed to send " + command + " to " + ip + ": " + e.getMessage());
        }
        return false;
    }

    static List<DeviceInfo> discoverViaUsb() {
        List<DeviceInfo> usbDevices = new ArrayList<>();
        try {
            String output = runCommandCaptureOutput(ADB, Arrays.asList("devices"));
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("List of devices")) continue;
                if (line.contains("\tdevice")) {
                    String serial = line.split("\\s+")[0];
                    if (!line.contains(":") && !serial.startsWith("adb-")) {
                        String deviceName = "USB Device (" + serial + ")";
                        try {
                            String modelOutput = runCommandCaptureOutput(ADB, Arrays.asList("-s", serial, "shell", "getprop", "ro.product.model"));
                            if (!modelOutput.trim().isEmpty()) {
                                deviceName = modelOutput.trim() + " (USB)";
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                        DeviceInfo device = new DeviceInfo(deviceName, serial, 0, "", "", "", "", 1, true);
                        usbDevices.add(device);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to list USB devices: " + e.getMessage());
        }
        return usbDevices;
    }

    static DeviceInfo tryDirectAdbConnect(String ip) {
        for (int port : STANDARD_ADB_PORTS) {
            try {
                String target = ip + ":" + port;
                String output = runCommandCaptureOutput(ADB, Arrays.asList("connect", target));
                if (output.toLowerCase().contains("connected") || output.toLowerCase().contains("already connected")) {
                    String deviceName = "Linkdroid Device";
                    try {
                        String modelOutput = runCommandCaptureOutput(ADB, Arrays.asList("-s", target, "shell", "getprop", "ro.product.model"));
                        if (!modelOutput.trim().isEmpty()) {
                            deviceName = modelOutput.trim();
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                    return new DeviceInfo(deviceName, ip, port);
                }
            } catch (Exception e) {
                // try next port
            }
        }
        return null;
    }

    static void printDeviceInfo(DeviceInfo device) {
        System.out.println();
        System.out.println("Found device:");
        System.out.println("  Name:          " + (device.name.isEmpty() ? "Unknown" : device.name));
        System.out.println("  IP:            " + device.ip + ":" + device.port);
        if (device.wifiIp != null && !device.wifiIp.isEmpty() && !device.wifiIp.equals(device.ip)) {
            System.out.println("  WiFi IP:       " + device.wifiIp);
        }
        if (device.tailscaleIp != null && !device.tailscaleIp.isEmpty() && !device.tailscaleIp.equals(device.ip)) {
            System.out.println("  Tailscale IP:  " + device.tailscaleIp);
        }
        if (device.deviceModel != null && !device.deviceModel.isEmpty()) {
            System.out.println("  Model:         " + device.deviceModel);
        }
        if (device.androidVersion != null && !device.androidVersion.isEmpty()) {
            System.out.println("  Android:       " + device.androidVersion);
        }
        System.out.println("  Beacon version:" + device.beaconVersion);
        System.out.println();
    }

    static List<NetworkAdapter> pickNetworkAdapters() {
        List<NetworkAdapter> allAdapters = discoverNetworkAdapters();

        if (allAdapters.isEmpty()) {
            System.out.println("No network adapters found.");
            return allAdapters;
        }

        System.out.println();
        System.out.println("Select network adapter to scan:");
        for (int i = 0; i < allAdapters.size(); i++) {
            NetworkAdapter a = allAdapters.get(i);
            System.out.println("  " + (i + 1) + ". " + a.displayName + " (" + a.adapterType + ")");
            for (String ip : a.allIps) {
                if (a.adapterType.equals("Ethernet") && ip.contains(":")) {
                    continue;
                }
                System.out.println("      IP: " + ip);
            }
        }
        System.out.println();

        while (true) {
            System.out.print("Your choice (1-" + allAdapters.size() + "): ");
            String choice = safeReadLine().trim();
            if (choice.matches("\\d+")) {
                int idx = Integer.parseInt(choice) - 1;
                if (idx >= 0 && idx < allAdapters.size()) {
                    List<NetworkAdapter> selected = new ArrayList<>();
                    selected.add(allAdapters.get(idx));
                    return selected;
                }
            }
            System.out.println("Invalid selection.");
        }
    }

    static DeviceInfo discoverViaApp(List<String> savedIps) {
        System.out.println();
        System.out.println("Make sure the Linkdroid app is running on your phone.");
        System.out.println("Keep the app open and the notification visible.");
        System.out.print("Press Enter when ready...");
        safeReadLine();
        System.out.println();

        System.out.println("Scanning for Linkdroid app...");

        List<NetworkAdapter> selectedAdapters = pickNetworkAdapters();

        List<String> ipsToCheck = new ArrayList<>(savedIps);
        if (!TAILSCALE_IP.isEmpty() && !ipsToCheck.contains(TAILSCALE_IP)) {
            ipsToCheck.add(TAILSCALE_IP);
        }

        List<DeviceInfo> allDevices = new ArrayList<>();

        // Parallel discovery: Tailscale CLI + mDNS
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<List<String>> tailscaleFuture = executor.submit(LinkdroidLauncher::discoverTailscaleIpsViaCli);
        Future<List<DeviceInfo>> mdnsFuture = executor.submit(() -> scanMdns(selectedAdapters));

        try {
            List<String> tailscaleIps = tailscaleFuture.get(TAILSCALE_CLI_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS);
            log("TAILSCALE_CLI: found " + tailscaleIps.size() + " devices");
            for (String ip : tailscaleIps) {
                if (!ipsToCheck.contains(ip)) {
                    ipsToCheck.add(ip);
                }
            }
        } catch (Exception e) {
            log("TAILSCALE_CLI: unavailable or timed out");
        }

        try {
            List<DeviceInfo> mdnsDevices = mdnsFuture.get(SCAN_DURATION_SECONDS + 2, TimeUnit.SECONDS);
            allDevices.addAll(mdnsDevices);
        } catch (Exception e) {
            log("mDNS: scan failed or timed out");
        }

        executor.shutdownNow();

        // Add subnet probe IPs from selected adapters only
        for (NetworkAdapter adapter : selectedAdapters) {
            log("  " + adapter.displayName + " (" + adapter.adapterType + ") IPs=" + String.join(", ", adapter.allIps) + " subnet=" + adapter.subnetPrefix);
            List<String> probeIps = adapter.getProbeIps();
            log("  Probe IPs for " + adapter.displayName + ": " + probeIps.size() + " addresses");
            for (String ip : probeIps) {
                if (!ipsToCheck.contains(ip)) {
                    ipsToCheck.add(ip);
                }
            }
        }

        // Probe beacon on all IPs (parallel for speed)
        List<DeviceInfo> beaconDevices = Collections.synchronizedList(new ArrayList<>());
        if (!allDevices.isEmpty()) {
            log("BEACON_PROBE: skipping subnet scan, already found " + allDevices.size() + " device(s) via mDNS/Tailscale");
        } else {
            ExecutorService probeExecutor = Executors.newFixedThreadPool(50);
            List<Future<DeviceInfo>> probeFutures = new ArrayList<>();
            for (String ip : ipsToCheck) {
                final String targetIp = ip;
                probeFutures.add(probeExecutor.submit(() -> {
                    log("BEACON_PROBE: probing " + targetIp + ":" + BEACON_PORT);
                    DeviceInfo d = scanTcpBeacon(targetIp);
                    if (d != null) {
                        log("BEACON: " + targetIp + " responded (" + d.name + ")");
                    } else {
                        log("BEACON_PROBE: no response from " + targetIp + ", trying direct ADB...");
                        d = tryDirectAdbConnect(targetIp);
                        if (d != null) {
                            log("ADB: direct connect to " + targetIp + " succeeded (" + d.name + ")");
                        }
                    }
                    return d;
                }));
            }
            for (Future<DeviceInfo> future : probeFutures) {
                try {
                    DeviceInfo d = future.get(10, TimeUnit.SECONDS);
                    if (d != null) {
                        beaconDevices.add(d);
                    }
                } catch (Exception e) {
                    // ignore probe errors
                }
            }
            probeExecutor.shutdownNow();
        }
        allDevices.addAll(beaconDevices);

        // Deduplicate by device identity (same deviceName or overlapping IPs)
        Map<String, DeviceInfo> uniqueDevices = new LinkedHashMap<>();
        for (DeviceInfo d : allDevices) {
            String key = d.deviceModel + "|" + d.name;
            boolean merged = false;
            for (Map.Entry<String, DeviceInfo> entry : uniqueDevices.entrySet()) {
                DeviceInfo existing = entry.getValue();
                if (!existing.name.isEmpty() && !d.name.isEmpty() && existing.name.equals(d.name)) {
                    mergeDeviceInfo(existing, d);
                    merged = true;
                    break;
                }
                if (!existing.ip.isEmpty() && !d.ip.isEmpty() && existing.ip.equals(d.ip)) {
                    mergeDeviceInfo(existing, d);
                    merged = true;
                    break;
                }
                if (d.allIps != null && existing.allIps != null) {
                    for (String ip : d.allIps) {
                        if (!ip.isEmpty() && existing.allIps.contains(ip)) {
                            mergeDeviceInfo(existing, d);
                            merged = true;
                            break;
                        }
                    }
                }
                if (merged) break;
            }
            if (!merged) {
                uniqueDevices.put(key, d);
            }
        }
        allDevices = new ArrayList<>(uniqueDevices.values());

        if (allDevices.isEmpty()) {
            return null;
        }

        if (allDevices.size() == 1) {
            DeviceInfo device = allDevices.get(0);
            printDeviceInfo(device);
            if (device.name == null || device.name.isEmpty()) {
                device.name = "Linkdroid Device";
            }
            handleCallState(device);
            return device;
        }

        System.out.println();
        System.out.println("Found " + allDevices.size() + " devices:");
        for (int i = 0; i < allDevices.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + allDevices.get(i).displayName());
        }
        System.out.println();

        while (true) {
            System.out.print("Select device (1-" + allDevices.size() + "): ");
            String choice = safeReadLine().trim();
            if (choice.matches("\\d+")) {
                int idx = Integer.parseInt(choice) - 1;
                if (idx >= 0 && idx < allDevices.size()) {
                    DeviceInfo selected = allDevices.get(idx);
                    printDeviceInfo(selected);
                    if (selected.name == null || selected.name.isEmpty()) {
                        selected.name = "Linkdroid Device";
                    }
                    handleCallState(selected);
                    return selected;
                }
            }
            System.out.println("Invalid selection.");
        }
    }

    static String promptManualEntry(String ip) {
        System.out.println();
        System.out.println("Enter connection details for " + ip + ":");
        System.out.println("  1. Enter port number");
        System.out.println("  2. Enter full IP:port");
        System.out.println();

        while (true) {
            System.out.print("Your choice (1 or 2): ");
            String choice = safeReadLine().trim();

            if (choice.equals("1")) {
                System.out.print("Enter port number: ");
                String port = safeReadLine().trim();
                if (port.matches("\\d+")) {
                    return ip + ":" + port;
                }
                System.out.println("Invalid port number.");
            } else if (choice.equals("2")) {
                System.out.print("Enter IP:port: ");
                String full = safeReadLine().trim();
                if (full.contains(":")) {
                    String[] parts = full.split(":");
                    if (parts.length == 2 && parts[1].matches("\\d+")) {
                        return full;
                    }
                }
                System.out.println("Invalid format. Use IP:port");
            } else {
                System.out.println("Please enter 1 or 2.");
            }
        }
    }

    static boolean validatePrerequisites() {
        if (!ADB.exists()) {
            System.out.println("ERROR: adb.exe not found at: " + ADB.getAbsolutePath());
            return false;
        }
        if (!SCRCPY.exists()) {
            System.out.println("ERROR: scrcpy.exe not found at: " + SCRCPY.getAbsolutePath());
            return false;
        }
        return true;
    }

    static void stopScrcpy() {
        if (SESSION.scrcpyProcess != null && SESSION.scrcpyProcess.isAlive()) {
            log("Stopping scrcpy process...");
            SESSION.scrcpyProcess.destroy();
            try {
                if (!SESSION.scrcpyProcess.waitFor(5, TimeUnit.SECONDS)) {
                    SESSION.scrcpyProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                SESSION.scrcpyProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            SESSION.scrcpyProcess = null;
        }
    }

    static void disconnectAdb(String target) {
        try {
            runCommand(ADB, Arrays.asList("disconnect", target), false);
        } catch (Exception e) {
            // ignore
        }
    }

    static void startHeartbeatMonitor(String target) {
        SESSION.heartbeatThread = new Thread(() -> {
            AtomicInteger failureCount = new AtomicInteger(0);
            while (!SESSION.stopRequested && SESSION.state == SessionState.CONNECTED) {
                try {
                    String output = runCommandCaptureOutput(ADB, Arrays.asList("-s", target, "shell", "echo", "heartbeat"));
                    if (output != null && output.trim().equals("heartbeat")) {
                        failureCount.set(0);
                    } else {
                        int failures = failureCount.incrementAndGet();
                        if (failures >= MAX_HEARTBEAT_FAILURES) {
                            log("ADB heartbeat failed " + failures + " times, declaring connection lost");
                            synchronized (SESSION.stateLock) {
                                if (SESSION.state == SessionState.CONNECTED) {
                                    SESSION.transitionTo(SessionState.RECONNECTING);
                                    SESSION.reconnectAttempt.set(0);
                                    SESSION.reconnectStartTime = System.currentTimeMillis();
                                    stopScrcpy();
                                    disconnectAdb(target);
                                }
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    int failures = failureCount.incrementAndGet();
                    if (failures >= MAX_HEARTBEAT_FAILURES) {
                        log("ADB heartbeat exception: " + e.getMessage() + ", declaring connection lost");
                        synchronized (SESSION.stateLock) {
                            if (SESSION.state == SessionState.CONNECTED) {
                                SESSION.transitionTo(SessionState.RECONNECTING);
                                SESSION.reconnectAttempt.set(0);
                                SESSION.reconnectStartTime = System.currentTimeMillis();
                                stopScrcpy();
                                disconnectAdb(target);
                            }
                        }
                        break;
                    }
                }
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_SECONDS * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "heartbeat-monitor");
        SESSION.heartbeatThread.setDaemon(true);
        SESSION.heartbeatThread.start();
    }

    static void stopHeartbeatMonitor() {
        if (SESSION.heartbeatThread != null && SESSION.heartbeatThread.isAlive()) {
            SESSION.heartbeatThread.interrupt();
            try {
                SESSION.heartbeatThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            SESSION.heartbeatThread = null;
        }
    }

    static DeviceInfo attemptReconnect(String originalTarget) {
        int attempt = SESSION.reconnectAttempt.incrementAndGet();
        long elapsed = (System.currentTimeMillis() - SESSION.reconnectStartTime) / 1000;
        log("RECONNECT: attempt " + attempt + "/" + MAX_RECONNECT_ATTEMPTS + " (elapsed: " + elapsed + "s)");

        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log("RECONNECT: max attempts reached, going IDLE");
            SESSION.transitionTo(SessionState.FAILED);
            return null;
        }

        if (elapsed >= MAX_RECONNECT_ATTEMPTS * RECONNECT_ATTEMPT_TIMEOUT_SECONDS) {
            log("RECONNECT: timeout exceeded, going IDLE");
            SESSION.transitionTo(SessionState.FAILED);
            return null;
        }

        SESSION.transitionTo(SessionState.DISCOVERING);
        List<String> saved = loadDevices();
        List<DeviceInfo> candidates = new ArrayList<>();

        // Try original target first (fast path for reconnection)
        if (originalTarget != null && !originalTarget.isEmpty()) {
            log("RECONNECT: trying original target " + originalTarget + " first");
            if (SESSION.selectedDevice != null && SESSION.selectedDevice.isUsb) {
                List<DeviceInfo> usbDevices = discoverViaUsb();
                for (DeviceInfo d : usbDevices) {
                    if (d.ip.equals(originalTarget)) {
                        candidates.add(d);
                        log("RECONNECT: USB device still connected");
                        break;
                    }
                }
            } else {
                String originalIp = originalTarget.split(":")[0];
                int originalPort = Integer.parseInt(originalTarget.split(":")[1]);
                DeviceInfo beacon = scanTcpBeacon(originalIp);
                if (beacon != null) {
                    candidates.add(beacon);
                    log("RECONNECT: original target beacon responded");
                } else {
                    DeviceInfo direct = tryDirectAdbConnect(originalIp);
                    if (direct != null) {
                        candidates.add(direct);
                        log("RECONNECT: original target ADB connect succeeded");
                    }
                }
            }
        }

        // Try saved IPs
        for (String savedIp : saved) {
            if (originalTarget != null && savedIp.equals(originalTarget.split(":")[0])) {
                continue;
            }
            DeviceInfo beacon = scanTcpBeacon(savedIp);
            if (beacon != null) {
                candidates.add(beacon);
            }
        }

        // Try Tailscale CLI
        List<String> tailscaleIps = discoverTailscaleIpsViaCli();
        for (String ip : tailscaleIps) {
            if (originalTarget != null && ip.equals(originalTarget.split(":")[0])) {
                continue;
            }
            DeviceInfo beacon = scanTcpBeacon(ip);
            if (beacon != null) {
                candidates.add(beacon);
            } else {
                DeviceInfo direct = tryDirectAdbConnect(ip);
                if (direct != null) {
                    candidates.add(direct);
                }
            }
        }

        // Fallback: scan local network adapters for Tailscale IPs
        if (candidates.isEmpty()) {
            List<NetworkAdapter> adapters = discoverNetworkAdapters();
            for (NetworkAdapter adapter : adapters) {
                for (String ip : adapter.allIps) {
                    if (ip.startsWith("100.") || ip.startsWith("fd7a:") || ip.startsWith("fc00:")) {
                        if (originalTarget != null && ip.equals(originalTarget.split(":")[0])) {
                            continue;
                        }
                        DeviceInfo beacon = scanTcpBeacon(ip);
                        if (beacon != null) {
                            candidates.add(beacon);
                        } else {
                            DeviceInfo direct = tryDirectAdbConnect(ip);
                            if (direct != null) {
                                candidates.add(direct);
                            }
                        }
                    }
                }
            }
            int totalLocalIps = 0;
            for (NetworkAdapter a : adapters) totalLocalIps += a.allIps.size();
            log("LOCAL_NETWORK: probed " + totalLocalIps + " local IPs across " + adapters.size() + " adapters, found " + candidates.size() + " candidates");
        }

        // Deduplicate
        Map<String, DeviceInfo> unique = new LinkedHashMap<>();
        for (DeviceInfo d : candidates) {
            if (!unique.containsKey(d.ip)) {
                unique.put(d.ip, d);
            }
        }
        candidates = new ArrayList<>(unique.values());

        if (candidates.isEmpty()) {
            log("RECONNECT: attempt " + attempt + " - no devices found");
            return null;
        }

        // Prefer original device
        DeviceInfo preferred = null;
        for (DeviceInfo d : candidates) {
            if (d.target().equals(originalTarget)) {
                preferred = d;
                break;
            }
        }

        if (preferred == null && !candidates.isEmpty()) {
            preferred = candidates.get(0);
            log("RECONNECT: original device not found, using " + preferred.displayName());
        }

        if (preferred != null) {
            log("RECONNECT: found device " + preferred.displayName());
            return preferred;
        }

        return null;
    }

    static void managedSession(DeviceInfo initialDevice, String maxSize) {
        String currentTarget = initialDevice.target();
        SESSION.selectedDevice = initialDevice;
        SESSION.transitionTo(SessionState.CONNECTING);

        while (!SESSION.stopRequested) {
            if (SESSION.state == SessionState.RECONNECTING) {
                SESSION.transitionTo(SessionState.DISCOVERING);
                DeviceInfo newDevice = attemptReconnect(currentTarget);
                if (newDevice == null) {
                    System.out.println("Could not reconnect. Phone may be out of range.");
                    SESSION.transitionTo(SessionState.FAILED);
                    break;
                }
                currentTarget = newDevice.target();
                SESSION.selectedDevice = newDevice;
            }

            if (SESSION.state == SessionState.FAILED) {
                System.out.println("Mirroring stopped after failed reconnection.");
                break;
            }

            SESSION.transitionTo(SessionState.CONNECTING);
            log("CONNECTING to " + currentTarget);

            try {
                String devicesOutput = runCommandCaptureOutput(ADB, Arrays.asList("devices"));
                for (String line : devicesOutput.split("\n")) {
                    if (line.startsWith(currentTarget.split(":")[0] + ":") && !line.contains("\tdevice")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length > 0) {
                            String stale = parts[0];
                            System.out.println("Cleaning stale connection: " + stale);
                            runCommand(ADB, Arrays.asList("disconnect", stale), true);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to clean stale connections: " + e.getMessage());
            }

            if (!SESSION.selectedDevice.isUsb) {
                try {
                    String connectOutput = runCommandCaptureOutput(ADB, Arrays.asList("connect", currentTarget));
                    System.out.println(connectOutput);
                    if (connectOutput.toLowerCase().contains("failed") || !connectOutput.contains("connected")) {
                        if (adbPair(currentTarget)) {
                            Thread.sleep(2000);
                            connectOutput = runCommandCaptureOutput(ADB, Arrays.asList("connect", currentTarget));
                            System.out.println(connectOutput);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("ADB connect error: " + e.getMessage());
                }
            }

            try {
                Thread.sleep(SCREEN_TIMEOUT_RESTORE_DELAY_MS);
                String devicesOutput = runCommandCaptureOutput(ADB, Arrays.asList("devices"));
            } catch (Exception e) {
                System.err.println("ADB devices check error: " + e.getMessage());
                SESSION.transitionTo(SessionState.RECONNECTING);
                continue;
            }

            Integer originalTimeout = getScreenTimeout(currentTarget);
            if (originalTimeout != null) {
                setScreenTimeout(currentTarget, SCREEN_TIMEOUT_DISABLE_MS);
                log("Screen timeout disabled (was " + originalTimeout + "ms)");
            }

            if (!SCRCPY.exists()) {
                System.out.println("ERROR: scrcpy not found at: " + SCRCPY.getAbsolutePath());
                SESSION.transitionTo(SessionState.FAILED);
                break;
            }

            System.out.println("Launching scrcpy...");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        SCRCPY.getAbsolutePath(),
                        "-s", currentTarget,
                        "--max-fps", "60",
                        "--max-size", maxSize,
                        "--video-bit-rate", "8M",
                        "--turn-screen-off"
                );
                pb.inheritIO();
                Process scrcpyProcess = pb.start();
                SESSION.scrcpyProcess = scrcpyProcess;
                SESSION.transitionTo(SessionState.CONNECTED);
                log("CONNECTED: scrcpy running for " + currentTarget);

                startHeartbeatMonitor(currentTarget);

                scrcpyProcess.waitFor();
                stopHeartbeatMonitor();

                log("scrcpy exited with code: " + SESSION.scrcpyProcess.exitValue());
                SESSION.scrcpyProcess = null;

                if (originalTimeout != null) {
                    setScreenTimeout(currentTarget, originalTimeout);
                    log("Screen timeout restored to " + originalTimeout + "ms");
                }

                if (SESSION.stopRequested) {
                    SESSION.transitionTo(SessionState.IDLE);
                    break;
                }

                System.out.println("scrcpy exited unexpectedly. Attempting to reconnect...");
                SESSION.transitionTo(SessionState.RECONNECTING);
                SESSION.reconnectAttempt.set(0);
                SESSION.reconnectStartTime = System.currentTimeMillis();

            } catch (Exception e) {
                System.err.println("Failed to launch scrcpy: " + e.getMessage());
                stopHeartbeatMonitor();
                if (SESSION.scrcpyProcess != null) {
                    SESSION.scrcpyProcess.destroy();
                    SESSION.scrcpyProcess = null;
                }
                if (originalTimeout != null) {
                    setScreenTimeout(currentTarget, originalTimeout);
                }
                SESSION.transitionTo(SessionState.RECONNECTING);
                SESSION.reconnectAttempt.set(0);
                SESSION.reconnectStartTime = System.currentTimeMillis();
            }
        }

        stopHeartbeatMonitor();
        stopScrcpy();
        disconnectAdb(currentTarget);
        SESSION.transitionTo(SessionState.IDLE);
        log("Session ended. Press Enter to exit.");
        safeReadLine();
    }

    static DeviceInfo discoverViaAppManaged(List<String> savedIps) {
        System.out.println();
        System.out.println("Make sure the Linkdroid app is running on your phone.");
        System.out.println("Keep the app open and the notification visible.");
        System.out.print("Press Enter when ready...");
        safeReadLine();
        System.out.println();

        System.out.println("Scanning for Linkdroid app...");

        List<NetworkAdapter> selectedAdapters = pickNetworkAdapters();

        List<String> ipsToCheck = new ArrayList<>(savedIps);
        if (!TAILSCALE_IP.isEmpty() && !ipsToCheck.contains(TAILSCALE_IP)) {
            ipsToCheck.add(TAILSCALE_IP);
        }

        List<DeviceInfo> allDevices = new ArrayList<>();

        // Parallel discovery: Tailscale CLI + mDNS
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<List<String>> tailscaleFuture = executor.submit(LinkdroidLauncher::discoverTailscaleIpsViaCli);
        Future<List<DeviceInfo>> mdnsFuture = executor.submit(() -> scanMdns(selectedAdapters));

        try {
            List<String> tailscaleIps = tailscaleFuture.get(TAILSCALE_CLI_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS);
            log("TAILSCALE_CLI: found " + tailscaleIps.size() + " devices");
            for (String ip : tailscaleIps) {
                if (!ipsToCheck.contains(ip)) {
                    ipsToCheck.add(ip);
                }
            }
        } catch (Exception e) {
            log("TAILSCALE_CLI: unavailable or timed out");
        }

        try {
            List<DeviceInfo> mdnsDevices = mdnsFuture.get(SCAN_DURATION_SECONDS + 2, TimeUnit.SECONDS);
            allDevices.addAll(mdnsDevices);
        } catch (Exception e) {
            log("mDNS: scan failed or timed out");
        }

        executor.shutdownNow();

        // Add subnet probe IPs from selected adapters only
        for (NetworkAdapter adapter : selectedAdapters) {
            log("  " + adapter.displayName + " (" + adapter.adapterType + ") IPs=" + String.join(", ", adapter.allIps) + " subnet=" + adapter.subnetPrefix);
            List<String> probeIps = adapter.getProbeIps();
            log("  Probe IPs for " + adapter.displayName + ": " + probeIps.size() + " addresses");
            for (String ip : probeIps) {
                if (!ipsToCheck.contains(ip)) {
                    ipsToCheck.add(ip);
                }
            }
        }

        // Probe beacon on all IPs (parallel for speed)
        List<DeviceInfo> beaconDevices = Collections.synchronizedList(new ArrayList<>());
        if (!allDevices.isEmpty()) {
            log("BEACON_PROBE: skipping subnet scan, already found " + allDevices.size() + " device(s) via mDNS/Tailscale");
        } else {
            ExecutorService probeExecutor = Executors.newFixedThreadPool(50);
            List<Future<DeviceInfo>> probeFutures = new ArrayList<>();
            for (String ip : ipsToCheck) {
                final String targetIp = ip;
                probeFutures.add(probeExecutor.submit(() -> {
                    log("BEACON_PROBE: probing " + targetIp + ":" + BEACON_PORT);
                    DeviceInfo d = scanTcpBeacon(targetIp);
                    if (d != null) {
                        log("BEACON: " + targetIp + " responded (" + d.name + ")");
                    } else {
                        log("BEACON_PROBE: no response from " + targetIp + ", trying direct ADB...");
                        d = tryDirectAdbConnect(targetIp);
                        if (d != null) {
                            log("ADB: direct connect to " + targetIp + " succeeded (" + d.name + ")");
                        }
                    }
                    return d;
                }));
            }
            for (Future<DeviceInfo> future : probeFutures) {
                try {
                    DeviceInfo d = future.get(10, TimeUnit.SECONDS);
                    if (d != null) {
                        beaconDevices.add(d);
                    }
                } catch (Exception e) {
                    // ignore probe errors
                }
            }
            probeExecutor.shutdownNow();
        }
        allDevices.addAll(beaconDevices);

        // Deduplicate by device identity (same deviceName or overlapping IPs)
        Map<String, DeviceInfo> uniqueDevices = new LinkedHashMap<>();
        for (DeviceInfo d : allDevices) {
            String key = d.deviceModel + "|" + d.name;
            boolean merged = false;
            for (Map.Entry<String, DeviceInfo> entry : uniqueDevices.entrySet()) {
                DeviceInfo existing = entry.getValue();
                if (!existing.name.isEmpty() && !d.name.isEmpty() && existing.name.equals(d.name)) {
                    mergeDeviceInfo(existing, d);
                    merged = true;
                    break;
                }
                if (!existing.ip.isEmpty() && !d.ip.isEmpty() && existing.ip.equals(d.ip)) {
                    mergeDeviceInfo(existing, d);
                    merged = true;
                    break;
                }
                if (d.allIps != null && existing.allIps != null) {
                    for (String ip : d.allIps) {
                        if (!ip.isEmpty() && existing.allIps.contains(ip)) {
                            mergeDeviceInfo(existing, d);
                            merged = true;
                            break;
                        }
                    }
                }
                if (merged) break;
            }
            if (!merged) {
                uniqueDevices.put(key, d);
            }
        }
        allDevices = new ArrayList<>(uniqueDevices.values());

        if (allDevices.isEmpty()) {
            return null;
        }

        if (allDevices.size() == 1) {
            DeviceInfo device = allDevices.get(0);
            printDeviceInfo(device);
            if (device.name == null || device.name.isEmpty()) {
                device.name = "Linkdroid Device";
            }
            handleCallState(device);
            return device;
        }

        System.out.println();
        System.out.println("Found " + allDevices.size() + " devices:");
        for (int i = 0; i < allDevices.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + allDevices.get(i).displayName());
        }
        System.out.println();

        while (true) {
            System.out.print("Select device (1-" + allDevices.size() + "): ");
            String choice = safeReadLine().trim();
            if (choice.matches("\\d+")) {
                int idx = Integer.parseInt(choice) - 1;
                if (idx >= 0 && idx < allDevices.size()) {
                    DeviceInfo selected = allDevices.get(idx);
                    printDeviceInfo(selected);
                    if (selected.name == null || selected.name.isEmpty()) {
                        selected.name = "Linkdroid Device";
                    }
                    handleCallState(selected);
                    return selected;
                }
            }
            System.out.println("Invalid selection.");
        }
    }

    static void handleCallState(DeviceInfo device) {
        if (device == null || device.callState == null || device.callState.isEmpty() || device.callState.equals("IDLE")) {
            return;
        }
        System.out.println();
        System.out.println("Phone call state: " + device.callState);
        if (device.callState.equals("RINGING")) {
            System.out.println("Incoming call detected!");
            while (true) {
                System.out.print("Answer or Decline? (A/d): ");
                String choice = safeReadLine().trim().toLowerCase();
                if (choice.isEmpty() || choice.equals("a") || choice.equals("answer")) {
                    System.out.println("Answering call...");
                    sendBeaconCommand(device.ip, "ANSWER");
                    break;
                } else if (choice.equals("d") || choice.equals("decline")) {
                    System.out.println("Declining call...");
                    sendBeaconCommand(device.ip, "DECLINE");
                    break;
                } else {
                    System.out.println("Please enter A (answer) or D (decline).");
                }
            }
        } else if (device.callState.equals("OFFHOOK")) {
            System.out.println("Call is active.");
            while (true) {
                System.out.print("Hang up? (Y/n): ");
                String choice = safeReadLine().trim().toLowerCase();
                if (choice.isEmpty() || choice.equals("y") || choice.equals("yes")) {
                    System.out.println("Hanging up...");
                    sendBeaconCommand(device.ip, "HANGUP");
                    break;
                } else if (choice.equals("n") || choice.equals("no")) {
                    break;
                } else {
                    System.out.println("Please enter Y (hang up) or N (keep call).");
                }
            }
        }
    }

    static void runDiagnostics() {
        System.out.println();
        System.out.println("========================================");
        System.out.println("  Linkdroid Diagnostics");
        System.out.println("========================================");
        System.out.println();

        log("DIAGNOSTICS: starting");

        System.out.println("[1] TAILSCALE CLI");
        System.out.println("----------------------------------------");
        String tailscaleCmd = findTailscaleExecutable();
        if (tailscaleCmd == null) {
            System.out.println("  tailscale executable: NOT FOUND");
            System.out.println("  Install Tailscale from https://tailscale.com/download");
        } else {
            System.out.println("  tailscale executable: " + tailscaleCmd);
            try {
                ProcessBuilder pb = new ProcessBuilder(tailscaleCmd, "status", "--json");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                StringBuilder json = new StringBuilder();
                Thread readerThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            json.append(line);
                        }
                    } catch (Exception e) {
                        // ignore read errors
                    }
                }, "tailscale-diag-reader");
                readerThread.setDaemon(true);
                readerThread.start();

                boolean finished = process.waitFor(20, TimeUnit.SECONDS);
                if (!finished) {
                    System.out.println("  tailscale status --json: TIMED OUT after 20s");
                    process.destroy();
                    readerThread.join(2000);
                } else if (process.exitValue() != 0) {
                    System.out.println("  tailscale status --json: FAILED with exit code " + process.exitValue());
                    readerThread.join(2000);
                } else {
                    readerThread.join(2000);
                    String body = json.toString();
                    System.out.println("  tailscale status --json output length: " + body.length() + " chars");
                    String localIp = extractLocalTailscaleIp(body);
                    System.out.println("  Local Tailscale IP: " + (localIp.isEmpty() ? "not found" : localIp));
                    
                    List<String> peerIps = new ArrayList<>();
                    int idx = 0;
                    while ((idx = body.indexOf("\"TailscaleIPs\":", idx)) != -1) {
                        int arrayStart = body.indexOf('[', idx);
                        int arrayEnd = body.indexOf(']', arrayStart);
                        if (arrayStart != -1 && arrayEnd != -1) {
                            String addressesStr = body.substring(arrayStart + 1, arrayEnd);
                            for (String addr : addressesStr.split(",")) {
                                addr = addr.replace("\"", "").trim();
                                if (!addr.isEmpty() && !addr.equals(localIp)) {
                                    peerIps.add(addr);
                                }
                            }
                        }
                        idx = arrayEnd + 1;
                    }
                    System.out.println("  Peer Tailscale IPs: " + (peerIps.isEmpty() ? "none found" : String.join(", ", peerIps)));
                    
                    int deviceCount = 0;
                    int peerIdx = body.indexOf("\"Peer\":");
                    if (peerIdx != -1) {
                        while (body.indexOf("\"HostName\":", peerIdx) != -1) {
                            deviceCount++;
                            peerIdx = body.indexOf("\"HostName\":", peerIdx) + 1;
                        }
                    }
                    System.out.println("  Devices in tailnet: " + deviceCount);
                }
            } catch (Exception e) {
                System.out.println("  Error: " + e.getMessage());
            }
        }

        System.out.println();
        System.out.println("[2] NETWORK INTERFACES");
        System.out.println("----------------------------------------");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            int count = 0;
            int tailscaleCount = 0;
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                count++;
                boolean isUp = ni.isUp();
                boolean isLoopback = ni.isLoopback();
                boolean supportsMulticast = ni.supportsMulticast();
                String name = ni.getName();
                String displayName = ni.getDisplayName();
                
                System.out.println("  Interface: " + displayName + " (" + name + ")");
                System.out.println("    Status: " + (isUp ? "up" : "down") + ", loopback=" + isLoopback + ", multicast=" + supportsMulticast);
                
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                List<String> ips = new ArrayList<>();
                boolean hasTailscaleIp = false;
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    String ip = addr.getHostAddress();
                    ips.add(ip);
                    if (ip.startsWith("100.") || ip.startsWith("fd7a:") || ip.startsWith("fc00:")) {
                        hasTailscaleIp = true;
                    }
                }
                System.out.println("    IPs: " + String.join(", ", ips));
                if (hasTailscaleIp) {
                    System.out.println("    Tailscale-like IPs: YES");
                    tailscaleCount++;
                }
            }
            System.out.println("  Total interfaces: " + count);
            System.out.println("  Interfaces with Tailscale-like IPs: " + tailscaleCount);
        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage());
        }

        System.out.println();
        System.out.println("[3] BEACON PROBE");
        System.out.println("----------------------------------------");
        List<String> probeTargets = new ArrayList<>();
        List<String> saved = loadDevices();
        for (String savedIp : saved) {
            String ip = savedIp.split(":")[0];
            if (!probeTargets.contains(ip)) {
                probeTargets.add(ip);
            }
        }
        if (!TAILSCALE_IP.isEmpty() && !probeTargets.contains(TAILSCALE_IP)) {
            probeTargets.add(TAILSCALE_IP);
        }
        List<String> tailscaleIps = discoverTailscaleIpsViaCli();
        for (String ip : tailscaleIps) {
            if (!probeTargets.contains(ip)) {
                probeTargets.add(ip);
            }
        }
        List<NetworkAdapter> adapters = discoverNetworkAdapters();
        System.out.println("  Adapters found: " + adapters.size());
        for (NetworkAdapter adapter : adapters) {
            System.out.println("    " + adapter.displayName + " (" + adapter.adapterType + ") IPs=" + String.join(", ", adapter.allIps) + " subnet=" + adapter.subnetPrefix);
            List<String> probeIps = adapter.getProbeIps();
            for (String ip : probeIps) {
                if (!probeTargets.contains(ip)) {
                    probeTargets.add(ip);
                }
            }
        }
        
        int responded = 0;
        for (String ip : probeTargets) {
            System.out.println("  Probing " + ip + ":" + BEACON_PORT + " ...");
            DeviceInfo beacon = scanTcpBeacon(ip);
            if (beacon != null) {
                System.out.println("    RESPONDED: " + beacon.name + " at " + beacon.ip + ":" + beacon.port);
                System.out.println("      WiFi IP: " + (beacon.wifiIp != null ? beacon.wifiIp : "N/A"));
                System.out.println("      Tailscale IP: " + (beacon.tailscaleIp != null ? beacon.tailscaleIp : "N/A"));
                System.out.println("      ADB port: " + beacon.port);
                System.out.println("      Android: " + (beacon.androidVersion != null ? beacon.androidVersion : "N/A"));
                System.out.println("      Model: " + (beacon.deviceModel != null ? beacon.deviceModel : "N/A"));
                responded++;
            } else {
                System.out.println("    no response");
            }
        }
        System.out.println("  Summary: " + responded + "/" + probeTargets.size() + " responded");

        System.out.println();
        System.out.println("[4] ADB CHECK");
        System.out.println("----------------------------------------");
        if (ADB.exists()) {
            System.out.println("  adb.exe: FOUND at " + ADB.getAbsolutePath());
            try {
                String version = runCommandCaptureOutput(ADB, Arrays.asList("version"));
                System.out.println("  adb version: " + version.trim().split("\n")[0]);
            } catch (Exception e) {
                System.out.println("  adb version: error running adb");
            }
            try {
                String devices = runCommandCaptureOutput(ADB, Arrays.asList("devices"));
                System.out.println("  Connected devices:");
                for (String line : devices.split("\n")) {
                    if (line.contains("\t")) {
                        System.out.println("    " + line.trim());
                    }
                }
            } catch (Exception e) {
                System.out.println("  adb devices: error running adb");
            }
        } else {
            System.out.println("  adb.exe: NOT FOUND at " + ADB.getAbsolutePath());
        }

        System.out.println();
        System.out.println("========================================");
        System.out.println("  Diagnostics complete");
        System.out.println("========================================");
        log("DIAGNOSTICS: complete");
    }

    static String findTailscaleExecutable() {
        String tailscaleCmd = null;
        try {
            Process which = Runtime.getRuntime().exec(new String[]{"where", "tailscale"});
            which.waitFor(3, TimeUnit.SECONDS);
            if (which.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(which.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        tailscaleCmd = line.trim();
                    }
                }
            }
        } catch (Exception e) {
            // try default paths
        }
        if (tailscaleCmd == null) {
            File[] fallbacks = new File[]{
                    new File("C:\\Program Files\\Tailscale\\tailscale.exe"),
                    new File("C:\\Program Files (x86)\\Tailscale\\tailscale.exe")
            };
            for (File fallback : fallbacks) {
                if (fallback.exists() && fallback.canExecute()) {
                    tailscaleCmd = fallback.getAbsolutePath();
                    break;
                }
            }
        }
        return tailscaleCmd;
    }

    static void runManagedSession(String[] args) {
        System.out.println("========================================");
        System.out.println("  Linkdroid Launcher");
        System.out.println("========================================");
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("Shutdown hook triggered");
            SESSION.stopRequested = true;
            stopScrcpy();
            stopHeartbeatMonitor();
            if (SESSION.selectedDevice != null) {
                disconnectAdb(SESSION.selectedDevice.target());
            }
        }));

        List<String> saved = loadDevices();
        loadSettings();

        System.out.println("How would you like to connect?");
        System.out.println("  1. Use Linkdroid App (auto-discover)");
        System.out.println("  2. Enter IP:port manually");
        System.out.println("  3. USB debugging");
        System.out.println("  4. Run diagnostics");
        System.out.println();

        while (true) {
            System.out.print("Your choice (1, 2, 3, or 4): ");
            String mode = safeReadLine().trim();

            if (mode.equals("1")) {
                if (!validatePrerequisites()) {
                    System.out.println("Please fix the above issues and try again.");
                    System.exit(1);
                }

                DeviceInfo appDevice = discoverViaAppManaged(saved);
                if (appDevice != null) {
                    System.out.println();
                    System.out.print("Connect to this device? (Y/n): ");
                    String confirm = safeReadLine().trim().toLowerCase();
                    if (confirm.isEmpty() || "y".equals(confirm) || "yes".equals(confirm)) {
                        saveDevice(appDevice.target());
                        String maxSize = chooseResolution();
                        managedSession(appDevice, maxSize);
                    } else if ("n".equals(confirm) || "no".equals(confirm)) {
                        System.out.println("Connection cancelled.");
                    } else {
                        System.out.println("Invalid choice.");
                    }
                } else {
                    System.out.println();
                    System.out.println("Could not find the Linkdroid app.");
                    System.out.println("Please make sure the app is running and try again.");
                    System.out.println();
                    System.out.print("Try again? (Y/n): ");
                    String retry = safeReadLine().trim().toLowerCase();
                    if (retry.isEmpty() || "y".equals(retry) || "yes".equals(retry)) {
                        continue;
                    } else {
                        System.out.println("Exiting.");
                        System.exit(0);
                    }
                }
            } else if (mode.equals("2")) {
                if (!validatePrerequisites()) {
                    System.out.println("Please fix the above issues and try again.");
                    System.exit(1);
                }

                System.out.println();
                if (saved.isEmpty()) {
                    System.out.println("No saved devices found.");
                    System.out.print("Enter your phone's IP address: ");
                    String ip = safeReadLine().trim();
                    if (!ip.isEmpty()) {
                        saveDevice(ip);
                        saved.add(ip);
                    } else {
                        System.out.println("No IP entered. Exiting.");
                        System.exit(0);
                    }
                }

                System.out.println("Saved device(s): " + String.join(", ", saved));
                System.out.println();

                String selectedIp = saved.get(0);
                if (saved.size() > 1) {
                    System.out.println("Select a device:");
                    for (int i = 0; i < saved.size(); i++) {
                        System.out.println("  " + (i + 1) + ". " + saved.get(i));
                    }
                    System.out.print("Enter device number: ");
                    String deviceChoice = safeReadLine().trim();
                    if (deviceChoice.matches("\\d+")) {
                        int idx = Integer.parseInt(deviceChoice) - 1;
                        if (idx >= 0 && idx < saved.size()) {
                            selectedIp = saved.get(idx);
                        }
                    }
                }

                String entry = promptManualEntry(selectedIp);
                String[] parts = entry.split(":");
                String ip = parts[0];
                int port = Integer.parseInt(parts[1]);

                System.out.println();
                System.out.println("Attempting to connect to " + ip + ":" + port + "...");
                String maxSize = chooseResolution();
                DeviceInfo device = new DeviceInfo("", ip, port);
                managedSession(device, maxSize);
            } else if (mode.equals("3")) {
                if (!validatePrerequisites()) {
                    System.out.println("Please fix the above issues and try again.");
                    System.exit(1);
                }

                System.out.println();
                System.out.println("Scanning for USB-connected devices...");
                List<DeviceInfo> usbDevices = discoverViaUsb();

                if (usbDevices.isEmpty()) {
                    System.out.println("No USB devices found.");
                    System.out.println("Make sure your phone is connected via USB and USB debugging is enabled.");
                    System.out.println();
                    continue;
                }

                DeviceInfo usbDevice;
                if (usbDevices.size() == 1) {
                    usbDevice = usbDevices.get(0);
                    System.out.println("Found USB device: " + usbDevice.displayName());
                } else {
                    System.out.println();
                    System.out.println("Found " + usbDevices.size() + " USB devices:");
                    for (int i = 0; i < usbDevices.size(); i++) {
                        System.out.println("  " + (i + 1) + ". " + usbDevices.get(i).displayName());
                    }
                    System.out.println();

                    while (true) {
                        System.out.print("Select device (1-" + usbDevices.size() + "): ");
                        String choice = safeReadLine().trim();
                        if (choice.matches("\\d+")) {
                            int idx = Integer.parseInt(choice) - 1;
                            if (idx >= 0 && idx < usbDevices.size()) {
                                usbDevice = usbDevices.get(idx);
                                break;
                            }
                        }
                        System.out.println("Invalid selection.");
                    }
                }

                System.out.println();
                System.out.print("Connect to this device? (Y/n): ");
                String confirm = safeReadLine().trim().toLowerCase();
                if (confirm.isEmpty() || "y".equals(confirm) || "yes".equals(confirm)) {
                    String maxSize = chooseResolution();
                    managedSession(usbDevice, maxSize);
                } else {
                    System.out.println("Connection cancelled.");
                }
            } else if (mode.equals("4")) {
                runDiagnostics();
                System.out.println();
                System.out.println("Diagnostics complete.");
                System.out.println();
            } else {
                System.out.println("Please enter 1, 2, 3, or 4.");
            }
        }
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--diagnose".equals(args[0])) {
            runDiagnostics();
            return;
        }

        String ip = null;
        String port = null;
        String resolution = null;
        boolean usbMode = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help":
                case "-h":
                    printHelp();
                    return;
                case "--ip":
                    if (i + 1 < args.length) {
                        ip = args[++i];
                    }
                    break;
                case "--port":
                    if (i + 1 < args.length) {
                        port = args[++i];
                    }
                    break;
                case "--resolution":
                case "-r":
                    if (i + 1 < args.length) {
                        resolution = args[++i];
                    }
                    break;
                case "--usb":
                    usbMode = true;
                    break;
                case "--diagnose":
                    runDiagnostics();
                    return;
                default:
                    System.out.println("Unknown option: " + args[i]);
                    printHelp();
                    System.exit(1);
            }
        }

        if (usbMode) {
            try {
                if (!validatePrerequisites()) {
                    System.err.println("Please fix the above issues and try again.");
                    System.exit(1);
                }
                String maxSize = resolution != null ? resolution : "720";
                List<DeviceInfo> usbDevices = discoverViaUsb();
                if (usbDevices.isEmpty()) {
                    System.err.println("No USB devices found.");
                    System.exit(1);
                }
                DeviceInfo usbDevice = usbDevices.get(0);
                if (usbDevices.size() > 1) {
                    System.out.println("Multiple USB devices found, using first: " + usbDevice.displayName());
                }
                managedSession(usbDevice, maxSize);
            } catch (Throwable t) {
                System.err.println("Fatal error: " + t.getMessage());
                t.printStackTrace();
                System.exit(1);
            }
            return;
        }

        if (ip != null && port != null) {
            try {
                if (!validatePrerequisites()) {
                    System.err.println("Please fix the above issues and try again.");
                    System.exit(1);
                }
                int portNum = Integer.parseInt(port);
                String maxSize = resolution != null ? resolution : "720";
                DeviceInfo device = new DeviceInfo("", ip, portNum);
                managedSession(device, maxSize);
            } catch (Throwable t) {
                System.err.println("Fatal error: " + t.getMessage());
                t.printStackTrace();
                System.exit(1);
            }
            return;
        }

        try {
            runManagedSession(args);
        } catch (Throwable t) {
            System.err.println("Fatal error: " + t.getMessage());
            t.printStackTrace();
            System.out.println();
            System.out.print("Press Enter to exit...");
            safeReadLine();
            System.exit(1);
        }
    }

    static void printHelp() {
        System.out.println("Linkdroid Launcher");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  LinkdroidLauncher [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --ip <address>       Connect directly to IP address");
        System.out.println("  --port <port>        ADB port (use with --ip)");
        System.out.println("  --usb                Connect via USB debugging");
        System.out.println("  --resolution <size>  Video resolution: 720, 1080, or 2160");
        System.out.println("  --diagnose           Run diagnostics and exit");
        System.out.println("  --help, -h           Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  LinkdroidLauncher --ip 100.119.196.79 --port 5555");
        System.out.println("  LinkdroidLauncher --ip 100.119.196.79 --port 5555 --resolution 1080");
        System.out.println("  LinkdroidLauncher --usb");
        System.out.println("  LinkdroidLauncher --diagnose");
    }
}
