/*
 * Copyright(c) 2026 Sven Trittler
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
 * v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
*/

package org.eclipse.cyclonedds.omg;

import java.io.Serializable;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.eclipse.cyclonedds.core.policy.*;

/** Immutable policy values implementing the normative policy interfaces. */
final class ExtraPolicies implements InvocationHandler, Serializable {
    private static final long serialVersionUID = 1L;
    private final transient ServiceEnvironment env;
    private final Class<?> api;
    @SuppressWarnings("serial") private final Map<String, Object> properties;
    private ExtraPolicies(ServiceEnvironment env, Class<?> api, Map<String, Object> properties) {
        this.env = env; this.api = api; this.properties = Map.copyOf(properties);
    }
    static final Set<Class<?>> EXTENSIONS = Set.of(Property.class, BinaryProperty.class,
        EntityName.class, IgnoreLocal.class, WriterBatching.class, PsmxInstances.class);
    static final Set<Class<?>> SUPPORTED = Set.of(Durability.class, ResourceLimits.class, Partition.class,
        Deadline.class, Lifespan.class, Liveliness.class, Ownership.class, OwnershipStrength.class,
        WriterDataLifecycle.class, TimeBasedFilter.class, DurabilityService.class,
        DestinationOrder.class, UserData.class, GroupData.class, TopicData.class,
        ReaderDataLifecycle.class, TypeConsistencyEnforcement.class, LatencyBudget.class,
        TransportPriority.class, EntityFactory.class, DataRepresentation.class, Presentation.class,
        Property.class, BinaryProperty.class, EntityName.class, IgnoreLocal.class, WriterBatching.class, PsmxInstances.class);
    static <T> T create(ServiceEnvironment env, Class<T> api) {
        Duration infinity = new Values.DdsDuration(env, Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        Map<String, Object> values;
        if (api == Durability.class) values = Map.of("Kind", Durability.Kind.VOLATILE);
        else if (api == ResourceLimits.class) values = Map.of("MaxSamples", -1, "MaxInstances", -1, "MaxSamplesPerInstance", -1);
        else if (api == Partition.class) values = Map.of("Name", Set.of(""));
        else if (api == DurabilityService.class) values = Map.of("ServiceCleanupDelay", new Values.DdsDuration(env, 0, TimeUnit.NANOSECONDS),
            "HistoryKind", History.Kind.KEEP_LAST, "HistoryDepth", 1, "MaxSamples", -1, "MaxInstances", -1, "MaxSamplesPerInstance", -1);
        else if (api == Deadline.class) values = Map.of("Period", infinity);
        else if (api == Lifespan.class) values = Map.of("Duration", infinity);
        else if (api == Liveliness.class) values = Map.of("Kind", Liveliness.Kind.AUTOMATIC, "LeaseDuration", infinity);
        else if (api == Ownership.class) values = Map.of("Kind", Ownership.Kind.SHARED);
        else if (api == OwnershipStrength.class) values = Map.of("Value", 0);
        else if (api == WriterDataLifecycle.class) values = Map.of("AutDisposeUnregisteredInstances", true);
        else if (api == TimeBasedFilter.class) values = Map.of("MinimumSeparation", new Values.DdsDuration(env, 0, TimeUnit.NANOSECONDS));
        else if (api == DestinationOrder.class) values = Map.of("Kind", DestinationOrder.Kind.BY_RECEPTION_TIMESTAMP);
        else if (api == UserData.class || api == GroupData.class || api == TopicData.class) values = Map.of("Value", new byte[0]);
        else if (api == ReaderDataLifecycle.class) values = Map.of("AutoPurgeNoWriterSamplesDelay", infinity, "AutoPurgeDisposedSamplesDelay", infinity);
        else if (TypeConsistencyEnforcement.class.isAssignableFrom(api)) values = Map.of("Kind", TypeConsistencyEnforcement.Kind.ASSIGNABLE_TYPE_CONSISTENCY,
            "IgnoreSequenceBounds", true, "IgnoreStringBounds", true, "IgnoreMemberNames", false,
            "PreventTypeWidening", false, "ForceTypeValidation", true);
        else if (api == LatencyBudget.class) values = Map.of("Duration", new Values.DdsDuration(env, 0, TimeUnit.NANOSECONDS));
        else if (api == TransportPriority.class) values = Map.of("Value", 0);
        else if (api == EntityFactory.class) values = Map.of("AutoEnableCreatedEntities", true);
        else if (api == DataRepresentation.class) values = Map.of("Value", List.of((short) 2));
        else if (api == Presentation.class) values = Map.of("AccessScope", Presentation.AccessScopeKind.INSTANCE, "CoherentAccess", false, "OrderedAccess", false);
        else if (api == Property.class || api == BinaryProperty.class) values = Map.of("Value", Map.of());
        else if (api == EntityName.class) values = Map.of("Name", "");
        else if (api == IgnoreLocal.class) values = Map.of("Kind", IgnoreLocal.Kind.NOTHING);
        else if (api == WriterBatching.class) values = Map.of("Enabled", false);
        else if (api == PsmxInstances.class) values = Map.of("Names", List.of());
        else throw Policies.unsupported(api.getSimpleName());
        return proxy(env, api, values);
    }
    static <T> T proxy(ServiceEnvironment env, Class<T> api, Map<String, Object> values) {
        Class<?> implementation = TypeConsistencyEnforcement.class.isAssignableFrom(api) ? TypeConsistency.class : api;
        return api.cast(Proxy.newProxyInstance(implementation.getClassLoader(), new Class<?>[]{implementation},
            new ExtraPolicies(env, implementation, values)));
    }
    public Object invoke(Object self, Method method, Object[] arguments) throws Throwable {
        Object[] args = arguments == null ? new Object[0] : arguments;
        String op = method.getName();
        if (op.equals("getEnvironment")) return env;
        if (op.equals("hashCode")) {
            int hash = api.hashCode();
            for (var entry : properties.entrySet()) hash += entry.getKey().hashCode() ^ (entry.getValue() instanceof byte[] bytes ? Arrays.hashCode(bytes) : entry.getValue().hashCode());
            return hash;
        }
        if (op.equals("toString")) return api.getSimpleName() + properties;
        if (op.equals("equals")) {
            if (args[0] == self) return true;
            if (!api.isInstance(args[0])) return false;
            for (var entry : properties.entrySet()) {
                String getter = (entry.getValue() instanceof Boolean ? "is" : "get") + entry.getKey();
                if (!Objects.deepEquals(entry.getValue(), api.getMethod(getter).invoke(args[0]))) return false;
            }
            return true;
        }
        if (op.startsWith("get") || op.startsWith("is")) {
            Object value = properties.get(op.substring(op.startsWith("get") ? 3 : 2));
            return value instanceof byte[] bytes ? bytes.clone() : value;
        }
        if (op.equals("requestedOfferedContract")) {
            if (api == DataRepresentation.class) return (Comparable<DataRepresentation>) other -> {
                if (Collections.disjoint(((DataRepresentation) self).getValue(), other.getValue()))
                    throw new IllegalArgumentException("Disjoint data representations have no request/offer ordering; use isRepresentationCompatible");
                return 0;
            };
            return self;
        }
        if (op.equals("compareTo")) {
            Object other = api.cast(args[0]);
            if (api == DataRepresentation.class) {
                List<Short> a = ((DataRepresentation) self).getValue(), b = ((DataRepresentation) other).getValue();
                for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                    int order = Short.compare(a.get(i), b.get(i)); if (order != 0) return order;
                }
                return Integer.compare(a.size(), b.size());
            }
            int kind = properties.containsKey("Kind") ? Integer.compare(((Enum<?>) properties.get("Kind")).ordinal(),
                ((Enum<?>) api.getMethod("getKind").invoke(other)).ordinal()) : 0;
            if (kind != 0) return kind;
            if (api == Presentation.class) {
                Presentation p = (Presentation) other;
                int scope = ((Presentation.AccessScopeKind) properties.get("AccessScope")).compareTo(p.getAccessScope());
                if (scope != 0) return scope;
                int coherent = Boolean.compare((Boolean) properties.get("CoherentAccess"), p.isCoherentAccess());
                return coherent != 0 ? coherent : Boolean.compare((Boolean) properties.get("OrderedAccess"), p.isOrderedAccess());
            }
            String duration = api == Deadline.class ? "Period" : api == Liveliness.class ? "LeaseDuration" : api == LatencyBudget.class ? "Duration" : null;
            if (duration != null) return ((Duration) api.getMethod("get" + duration).invoke(other)).compareTo((Duration) properties.get(duration));
            return 0;
        }
        if ((api == Property.class || api == BinaryProperty.class) && (op.equals("withValue") || op.equals("without"))) {
            String name = PolicyText.require((String) args[0]);
            @SuppressWarnings("unchecked") var entries = new LinkedHashMap<>((Map<String, Object>) properties.get("Value"));
            if (op.equals("without")) entries.remove(name);
            else entries.put(name, api == Property.class ? new Property.Value((String) args[1], (Boolean) args[2])
                : new BinaryProperty.Value((byte[]) args[1], (Boolean) args[2]));
            return proxy(env, api, Map.of("Value", Collections.unmodifiableMap(entries)));
        }
        if (op.startsWith("with")) {
            var changed = new LinkedHashMap<>(properties);
            String property = op.substring(4);
            Object value;
            if (args.length == 0) {
                property = api == Presentation.class ? "AccessScope" : "Kind";
                String variant = op.substring(4);
                if (api == DestinationOrder.class) value = variant.equals("ReceptionTimestamp") ? DestinationOrder.Kind.BY_RECEPTION_TIMESTAMP : DestinationOrder.Kind.BY_SOURCE_TIMESTAMP;
                else if (TypeConsistencyEnforcement.class.isAssignableFrom(api)) value = switch (variant) {
                    case "ExactTypeTypeConsistency" -> TypeConsistencyEnforcement.Kind.EXACT_TYPE_TYPE_CONSISTENCY;
                    case "ExactNameTypeConsistency" -> TypeConsistencyEnforcement.Kind.EXACT_NAME_TYPE_CONSISTENCY;
                    case "DeclaredTypeConsistency" -> TypeConsistencyEnforcement.Kind.DECLARED_TYPE_CONSISTENCY;
                    case "AssignableTypeConsistency" -> TypeConsistencyEnforcement.Kind.ASSIGNABLE_TYPE_CONSISTENCY;
                    default -> throw Policies.unsupported(op);
                };
                else if (api == Presentation.class) value = Presentation.AccessScopeKind.valueOf(variant.toUpperCase(Locale.ROOT));
                else if (api == Durability.class) value = switch (variant) {
                    case "Volatile" -> Durability.Kind.VOLATILE;
                    case "TransientLocal" -> Durability.Kind.TRANSIENT_LOCAL;
                    default -> throw Policies.unsupported("External durability service");
                };
                else if (api == Ownership.class) value = variant.equals("Shared") ? Ownership.Kind.SHARED : Ownership.Kind.EXCLUSIVE;
                else value = switch (variant) {
                    case "Automatic" -> Liveliness.Kind.AUTOMATIC;
                    case "ManualByParticipant" -> Liveliness.Kind.MANUAL_BY_PARTICIPANT;
                    case "ManualByTopic" -> Liveliness.Kind.MANUAL_BY_TOPIC;
                    default -> throw Policies.unsupported(op);
                };
            } else if (args.length == 3 && args[0] instanceof byte[] bytes) {
                int offset = (Integer) args[1], length = (Integer) args[2];
                Objects.checkFromIndexSize(offset, length, bytes.length);
                value = Arrays.copyOfRange(bytes, offset, offset + length);
            } else if (api == DataRepresentation.class) {
                if (args[0] instanceof short[] ids) {
                    List<Short> list = new ArrayList<>(); for (short id : ids) list.add(id); value = List.copyOf(list);
                } else value = List.copyOf((List<?>) args[0]);
                if (((List<?>) value).isEmpty()) throw new IllegalArgumentException("Representation list must not be empty");
            } else if (args.length == 2) value = new Values.DdsDuration(env, (Long) args[0], (TimeUnit) args[1]);
            else value = Objects.requireNonNull(args[0]);
            if (!properties.containsKey(property)) throw Policies.unsupported(op);
            if (api == Durability.class && value instanceof Durability.Kind kind && kind.ordinal() > 1)
                throw Policies.unsupported("External durability service");
            if (value instanceof Duration duration && duration.getEnvironment() != env) throw new IllegalArgumentException("Foreign duration");
            if ((api == ResourceLimits.class || api == DurabilityService.class && property.startsWith("Max")) && ((Integer) value == 0 || (Integer) value < -1)) throw new IllegalArgumentException("Resource limit must be positive or LENGTH_UNLIMITED");
            if (api == DurabilityService.class && property.equals("HistoryDepth") && (Integer) value < 1) throw new IllegalArgumentException("History depth must be positive");
            if (api == EntityName.class) value = PolicyText.require((String) value);
            if (api == PsmxInstances.class) {
                List<String> names = new ArrayList<>();
                for (Object name : (Collection<?>) value) names.add(PolicyText.require((String) name));
                if (new HashSet<>(names).size() != names.size()) throw new IllegalArgumentException("Duplicate PSMX instance");
                value = List.copyOf(names);
            }
            if (api == Partition.class) {
                Set<String> names = new LinkedHashSet<>();
                for (Object name : (Collection<?>) value) {
                    String text = (String) Objects.requireNonNull(name);
                    if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in partition");
                    names.add(text);
                }
                value = Collections.unmodifiableSet(names);
            }
            changed.put(property, value); return proxy(env, api, changed);
        }
        throw Policies.unsupported(op);
    }
}
