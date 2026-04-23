package treeone.multiplace;

import java.util.ArrayList;
import java.util.List;

public class MultiPlaceConfig {
    public PlaceSession session = new PlaceSession();

    public enum Mode { PLACE, BREAK }

    public static class PlaceSession {
        public String itemName = "";
        public List<Pos> positions = new ArrayList<>();
        public boolean active = false;
        public boolean sneak = false;
        public boolean limitContainers = false;
        public Mode mode = Mode.PLACE;
    }

    public static class Pos {
        public int x, y, z;
        @SuppressWarnings("unused")
        public Pos() {}
        public Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

        @Override
        public String toString() { return "[" + x + ", " + y + ", " + z + "]"; }
    }
}