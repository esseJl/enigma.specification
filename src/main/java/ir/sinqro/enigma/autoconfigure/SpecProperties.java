package ir.sinqro.enigma.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.specification")
public class SpecProperties {

    /** IANA zone id used to interpret date/time strings without an explicit offset. */
    private String timezone = "UTC";

    private final Spec spec = new Spec();

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public Spec getSpec() { return spec; }

    public static class Spec {
        private int maxFilterDepth = 32;
        private int maxSortFields = 20;
        private int maxFilterNodes = 1000;
        private int maxJoins = 30;
        private int maxInSize = 500;

        public int getMaxFilterDepth() { return maxFilterDepth; }
        public void setMaxFilterDepth(int maxFilterDepth) { this.maxFilterDepth = maxFilterDepth; }

        public int getMaxSortFields() { return maxSortFields; }
        public void setMaxSortFields(int maxSortFields) { this.maxSortFields = maxSortFields; }

        public int getMaxFilterNodes() { return maxFilterNodes; }
        public void setMaxFilterNodes(int maxFilterNodes) { this.maxFilterNodes = maxFilterNodes; }

        public int getMaxJoins() { return maxJoins; }
        public void setMaxJoins(int maxJoins) { this.maxJoins = maxJoins; }

        public int getMaxInSize() { return maxInSize; }
        public void setMaxInSize(int maxInSize) { this.maxInSize = maxInSize; }
    }
}