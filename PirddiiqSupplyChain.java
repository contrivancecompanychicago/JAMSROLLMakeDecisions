import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A small, dependency-free in-memory supply-chain service for PIRDDIIQ Lab.
 * Tracks catalog items, supplier lots, inventory, order reservations, fulfillment,
 * and reorder alerts. This is a virtual/demo implementation; it is not a validated
 * production inventory, purchasing, or clinical system.
 */
public final class PirddiiqSupplyChain {
    private final Clock clock;
    private final Map<String, SupplyItem> catalog = new LinkedHashMap<String, SupplyItem>();
    private final Map<String, Lot> lots = new LinkedHashMap<String, Lot>();
    private final Map<String, SupplyOrder> orders = new LinkedHashMap<String, SupplyOrder>();
    private final Map<String, Shipment> shipments = new LinkedHashMap<String, Shipment>();
    private int nextOrderNumber = 1;
    private int nextShipmentNumber = 1;

    public PirddiiqSupplyChain() {
        this(Clock.systemDefaultZone());
    }

    /** Clock injection keeps expiry and receiving behavior deterministic in tests. */
    public PirddiiqSupplyChain(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Add a SKU to the catalog. Reorder thresholds are expressed in the item's unit. */
    public synchronized SupplyItem addItem(String sku, String name, String category,
                                           String unit, int reorderPoint,
                                           boolean expirationTracked) {
        requireText(sku, "sku");
        requireText(name, "name");
        requireText(category, "category");
        requireText(unit, "unit");
        if (reorderPoint < 0) {
            throw new IllegalArgumentException("reorderPoint must be zero or greater");
        }
        if (catalog.containsKey(sku)) {
            throw new IllegalArgumentException("SKU already exists: " + sku);
        }
        SupplyItem item = new SupplyItem(sku, name, category, unit, reorderPoint, expirationTracked);
        catalog.put(sku, item);
        return item;
    }

    /** Receive a supplier lot into inventory. Expiry is required for tracked items. */
    public synchronized Lot receive(String lotId, String sku, String supplier,
                                    int quantity, LocalDate expiresOn, String location) {
        requireText(lotId, "lotId");
        requireText(supplier, "supplier");
        requireText(location, "location");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        SupplyItem item = requireItem(sku);
        if (lots.containsKey(lotId)) {
            throw new IllegalArgumentException("Lot ID already exists: " + lotId);
        }
        if (item.isExpirationTracked() && expiresOn == null) {
            throw new IllegalArgumentException("Expiry date is required for SKU " + sku);
        }
        if (expiresOn != null && expiresOn.isBefore(today())) {
            throw new IllegalArgumentException("Cannot receive an already-expired lot");
        }
        Lot lot = new Lot(lotId, sku, supplier, quantity, expiresOn, location, today());
        lots.put(lotId, lot);
        return lot;
    }

    /** Create an order. The map contains SKU-to-quantity lines; it is copied on input. */
    public synchronized SupplyOrder createOrder(Map<String, Integer> requestedItems) {
        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new IllegalArgumentException("An order must contain at least one item");
        }
        Map<String, Integer> lines = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : requestedItems.entrySet()) {
            requireItem(entry.getKey());
            if (entry.getValue() == null || entry.getValue().intValue() <= 0) {
                throw new IllegalArgumentException("Order quantities must be greater than zero");
            }
            lines.put(entry.getKey(), entry.getValue());
        }
        String id = String.format("PIR-%06d", Integer.valueOf(nextOrderNumber++));
        SupplyOrder order = new SupplyOrder(id, lines, today());
        orders.put(id, order);
        return order;
    }

    /**
     * Reserve all order lines using first-expiring-first-out allocation. The operation
     * is all-or-nothing: a shortage leaves every lot and the order unchanged.
     */
    public synchronized SupplyOrder reserveOrder(String orderId) {
        SupplyOrder order = requireOrder(orderId);
        if (order.status != OrderStatus.DRAFT) {
            throw new IllegalStateException("Only draft orders can be reserved");
        }

        Map<String, Integer> staged = new LinkedHashMap<String, Integer>();
        List<Allocation> plan = new ArrayList<Allocation>();
        for (Map.Entry<String, Integer> line : order.lines.entrySet()) {
            int remaining = line.getValue().intValue();
            List<Lot> eligible = eligibleLots(line.getKey());
            for (Lot lot : eligible) {
                int alreadyStaged = valueOrZero(staged.get(lot.id));
                int available = lot.quantity - lot.reserved - alreadyStaged;
                if (available <= 0) {
                    continue;
                }
                int allocated = Math.min(available, remaining);
                staged.put(lot.id, Integer.valueOf(alreadyStaged + allocated));
                plan.add(new Allocation(lot.id, line.getKey(), allocated));
                remaining -= allocated;
                if (remaining == 0) {
                    break;
                }
            }
            if (remaining > 0) {
                throw new IllegalStateException("Insufficient unexpired stock for SKU " + line.getKey());
            }
        }
        for (Map.Entry<String, Integer> allocation : staged.entrySet()) {
            lots.get(allocation.getKey()).reserved += allocation.getValue().intValue();
        }
        order.allocations.addAll(plan);
        order.status = OrderStatus.RESERVED;
        return order;
    }

    /** Fulfill a reserved order, deduct stock, and record a shipment. */
    public synchronized Shipment fulfillOrder(String orderId, String carrierReference) {
        SupplyOrder order = requireOrder(orderId);
        if (order.status != OrderStatus.RESERVED) {
            throw new IllegalStateException("Only reserved orders can be fulfilled");
        }
        for (Allocation allocation : order.allocations) {
            Lot lot = lots.get(allocation.lotId);
            lot.reserved -= allocation.quantity;
            lot.quantity -= allocation.quantity;
        }
        order.status = OrderStatus.FULFILLED;
        String shipmentId = String.format("SHIP-%06d", Integer.valueOf(nextShipmentNumber++));
        Shipment shipment = new Shipment(shipmentId, order.id, carrierReference, today(), order.allocations);
        shipments.put(shipmentId, shipment);
        return shipment;
    }

    /** Cancel a draft or reserved order. Reserved stock is released, not consumed. */
    public synchronized SupplyOrder cancelOrder(String orderId) {
        SupplyOrder order = requireOrder(orderId);
        if (order.status != OrderStatus.DRAFT && order.status != OrderStatus.RESERVED) {
            throw new IllegalStateException("Only draft or reserved orders can be cancelled");
        }
        if (order.status == OrderStatus.RESERVED) {
            for (Allocation allocation : order.allocations) {
                lots.get(allocation.lotId).reserved -= allocation.quantity;
            }
        }
        order.status = OrderStatus.CANCELLED;
        return order;
    }

    /** Available stock excludes reserved and expired units. */
    public synchronized int availableQuantity(String sku) {
        requireItem(sku);
        int total = 0;
        for (Lot lot : lots.values()) {
            if (lot.sku.equals(sku) && !isExpired(lot)) {
                total += lot.quantity - lot.reserved;
            }
        }
        return total;
    }

    /** On-hand quantity includes reserved and expired stock still physically recorded. */
    public synchronized int onHandQuantity(String sku) {
        requireItem(sku);
        int total = 0;
        for (Lot lot : lots.values()) {
            if (lot.sku.equals(sku)) {
                total += lot.quantity;
            }
        }
        return total;
    }

    /** Return catalog items whose usable stock is at or below their reorder point. */
    public synchronized List<ReorderAlert> getReorderAlerts() {
        List<ReorderAlert> alerts = new ArrayList<ReorderAlert>();
        for (SupplyItem item : catalog.values()) {
            int available = availableQuantity(item.sku);
            if (available <= item.reorderPoint) {
                alerts.add(new ReorderAlert(item, available));
            }
        }
        return Collections.unmodifiableList(alerts);
    }

    public synchronized SupplyItem getItem(String sku) {
        return requireItem(sku);
    }

    public synchronized List<Lot> getLots() {
        return Collections.unmodifiableList(new ArrayList<Lot>(lots.values()));
    }

    public synchronized List<SupplyOrder> getOrders() {
        return Collections.unmodifiableList(new ArrayList<SupplyOrder>(orders.values()));
    }

    public synchronized List<Shipment> getShipments() {
        return Collections.unmodifiableList(new ArrayList<Shipment>(shipments.values()));
    }

    private List<Lot> eligibleLots(String sku) {
        List<Lot> eligible = new ArrayList<Lot>();
        for (Lot lot : lots.values()) {
            if (lot.sku.equals(sku) && !isExpired(lot) && lot.quantity > lot.reserved) {
                eligible.add(lot);
            }
        }
        Collections.sort(eligible, (a, b) -> {
            if (a.expiresOn == null && b.expiresOn == null) return a.receivedOn.compareTo(b.receivedOn);
            if (a.expiresOn == null) return 1;
            if (b.expiresOn == null) return -1;
            int expiryOrder = a.expiresOn.compareTo(b.expiresOn);
            return expiryOrder != 0 ? expiryOrder : a.receivedOn.compareTo(b.receivedOn);
        });
        return eligible;
    }

    private boolean isExpired(Lot lot) {
        return lot.expiresOn != null && lot.expiresOn.isBefore(today());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private SupplyItem requireItem(String sku) {
        SupplyItem item = catalog.get(sku);
        if (item == null) throw new IllegalArgumentException("Unknown SKU: " + sku);
        return item;
    }

    private SupplyOrder requireOrder(String orderId) {
        SupplyOrder order = orders.get(orderId);
        if (order == null) throw new IllegalArgumentException("Unknown order: " + orderId);
        return order;
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    /** Run a small end-to-end example: receive, reserve, fulfill, and report stock. */
    public static void main(String[] args) {
        PirddiiqSupplyChain supplyChain = new PirddiiqSupplyChain();
        supplyChain.addItem("PPE-N95", "N95 respirator", "Personal protective equipment", "each", 20, true);
        supplyChain.receive("LOT-1001", "PPE-N95", "Example supplier", 100,
                LocalDate.now().plusMonths(18), "Main storeroom");
        Map<String, Integer> request = new LinkedHashMap<String, Integer>();
        request.put("PPE-N95", Integer.valueOf(25));
        SupplyOrder order = supplyChain.createOrder(request);
        supplyChain.reserveOrder(order.getId());
        Shipment shipment = supplyChain.fulfillOrder(order.getId(), "DEMO-TRACKING");
        System.out.println("PIRDDIIQ Lab virtual supply chain demo");
        System.out.println("Shipment " + shipment.getId() + " for order " + order.getId());
        System.out.println("N95 respirators available: " + supplyChain.availableQuantity("PPE-N95"));
    }

    public enum OrderStatus { DRAFT, RESERVED, FULFILLED, CANCELLED }

    public static final class SupplyItem {
        private final String sku, name, category, unit;
        private final int reorderPoint;
        private final boolean expirationTracked;
        private SupplyItem(String sku, String name, String category, String unit,
                           int reorderPoint, boolean expirationTracked) {
            this.sku = sku; this.name = name; this.category = category; this.unit = unit;
            this.reorderPoint = reorderPoint; this.expirationTracked = expirationTracked;
        }
        public String getSku() { return sku; }
        public String getName() { return name; }
        public String getCategory() { return category; }
        public String getUnit() { return unit; }
        public int getReorderPoint() { return reorderPoint; }
        public boolean isExpirationTracked() { return expirationTracked; }
    }

    public static final class Lot {
        private final String id, sku, supplier, location;
        private final LocalDate expiresOn, receivedOn;
        private int quantity, reserved;
        private Lot(String id, String sku, String supplier, int quantity,
                    LocalDate expiresOn, String location, LocalDate receivedOn) {
            this.id = id; this.sku = sku; this.supplier = supplier; this.quantity = quantity;
            this.expiresOn = expiresOn; this.location = location; this.receivedOn = receivedOn;
        }
        public String getId() { return id; }
        public String getSku() { return sku; }
        public String getSupplier() { return supplier; }
        public String getLocation() { return location; }
        public LocalDate getExpiresOn() { return expiresOn; }
        public LocalDate getReceivedOn() { return receivedOn; }
        public int getQuantity() { return quantity; }
        public int getReserved() { return reserved; }
        public int getAvailable() { return quantity - reserved; }
    }

    public static final class SupplyOrder {
        private final String id;
        private final Map<String, Integer> lines;
        private final LocalDate createdOn;
        private final List<Allocation> allocations = new ArrayList<Allocation>();
        private OrderStatus status = OrderStatus.DRAFT;
        private SupplyOrder(String id, Map<String, Integer> lines, LocalDate createdOn) {
            this.id = id;
            this.lines = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(lines));
            this.createdOn = createdOn;
        }
        public String getId() { return id; }
        public Map<String, Integer> getLines() { return lines; }
        public LocalDate getCreatedOn() { return createdOn; }
        public OrderStatus getStatus() { return status; }
        public List<Allocation> getAllocations() {
            return Collections.unmodifiableList(new ArrayList<Allocation>(allocations));
        }
    }

    public static final class Allocation {
        private final String lotId, sku;
        private final int quantity;
        private Allocation(String lotId, String sku, int quantity) {
            this.lotId = lotId; this.sku = sku; this.quantity = quantity;
        }
        public String getLotId() { return lotId; }
        public String getSku() { return sku; }
        public int getQuantity() { return quantity; }
    }

    public static final class Shipment {
        private final String id, orderId, carrierReference;
        private final LocalDate shippedOn;
        private final List<Allocation> contents;
        private Shipment(String id, String orderId, String carrierReference,
                         LocalDate shippedOn, List<Allocation> contents) {
            this.id = id; this.orderId = orderId; this.carrierReference = carrierReference;
            this.shippedOn = shippedOn;
            this.contents = Collections.unmodifiableList(new ArrayList<Allocation>(contents));
        }
        public String getId() { return id; }
        public String getOrderId() { return orderId; }
        public String getCarrierReference() { return carrierReference; }
        public LocalDate getShippedOn() { return shippedOn; }
        public List<Allocation> getContents() { return contents; }
    }

    public static final class ReorderAlert {
        private final SupplyItem item;
        private final int availableQuantity;
        private ReorderAlert(SupplyItem item, int availableQuantity) {
            this.item = item; this.availableQuantity = availableQuantity;
        }
        public SupplyItem getItem() { return item; }
        public int getAvailableQuantity() { return availableQuantity; }
        public int getReorderPoint() { return item.getReorderPoint(); }
    }
}
