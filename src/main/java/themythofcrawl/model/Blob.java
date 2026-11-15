package themythofcrawl.model;

/** Bytes of a request / response that may live on the heap or in the workspace folder. */
public interface Blob {
    byte[] bytes();

    int size();

    static Blob of(byte[] data) {
        return data == null ? null : new Mem(data);
    }

    static byte[] bytesOf(Blob b) {
        return b == null ? null : b.bytes();
    }

    record Mem(byte[] data) implements Blob {
        @Override
        public byte[] bytes() {
            return data;
        }

        @Override
        public int size() {
            return data.length;
        }
    }
}
