package org.matsim.agentic;

/** Offline fixtures preserve original departures unless a test explicitly exercises shifts. */
final class MorningJointTestSupport {
    static void useBundledDtd(java.nio.file.Path file) throws java.io.IOException {
        String xml;
        try (var reader=org.matsim.core.utils.io.IOUtils.getBufferedReader(file.toString())) {
            xml=reader.lines().collect(java.util.stream.Collectors.joining("\n"));
        }
        var reference=java.util.regex.Pattern.compile("http://www\\.matsim\\.org/files/dtd/([^\"\\s]+\\.dtd)").matcher(xml);
        xml=reference.replaceAll(match -> {
            var resource=org.matsim.core.config.ConfigUtils.class.getResource("/dtd/"+match.group(1));
            if(resource==null) throw new AssertionError("Missing bundled schema: "+match.group(1));
            return java.util.regex.Matcher.quoteReplacement(resource.toExternalForm());
        });
        try (var writer=org.matsim.core.utils.io.IOUtils.getBufferedWriter(file.toString())) {
            writer.write(xml);
        }
    }

    static java.util.List<String> routeIds(String user) {
        var actions = java.util.regex.Pattern.compile("AVAILABLE ACTIONS FOR THIS TRIP: (\\[[^\\n]+?\\])").matcher(user);
        if (!actions.find()) throw new AssertionError("Missing available actions");
        var result = new java.util.ArrayList<String>();
        Json.readTree(actions.group(1)).forEach(id -> result.add(id.asText()));
        return result;
    }
    static String firstRoute(String user) { return routeIds(user).getFirst(); }

    static java.util.List<com.fasterxml.jackson.databind.JsonNode> tableRows(String user, String title) {
        int begin = user.indexOf(title);
        if (begin<0) throw new AssertionError("Missing table: " + title);
        boolean columns = false;
        var rows = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (String line : user.substring(begin).split("\n")) {
            if (line.startsWith("columns=")) { columns = true; continue; }
            if (columns && line.startsWith("[")) rows.add(Json.readTree(line));
            else if (!rows.isEmpty()) break;
        }
        return rows;
    }

    static String withDeparture(String content, String user) {
        var node = Json.readTree(content);
        if (node.isObject() && node.has("routeId") && !node.has("departureSeconds") && user.contains("Allowed departureSeconds:")) {
            var clock = java.util.regex.Pattern.compile("Planned departure: (\\d+):(\\d+):(\\d+)").matcher(user);
            if (!clock.find()) throw new AssertionError("Missing original departure in prompt");
            double time = Integer.parseInt(clock.group(1))*3600 + Integer.parseInt(clock.group(2))*60 + Integer.parseInt(clock.group(3));
            ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("departureSeconds", time);
        }
        return Json.write(node);
    }
}
