package chihalu.automated.tree.harvesting.logic;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BiFunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

final class PendingPlantStorage extends PersistentState {
	static final String STORAGE_ID = "automated_tree_harvesting_pending_plants";
	private static final Codec<PendingPlantStorage> CODEC = Codec.PASSTHROUGH.comapFlatMap(
		dynamic -> {
			Dynamic<NbtElement> asNbt = dynamic.convert(NbtOps.INSTANCE);
			NbtElement element = asNbt.getValue();
			if (!(element instanceof NbtCompound compound)) {
				return DataResult.error(() -> "Expected NbtCompound");
			}
			return DataResult.success(fromCompound(compound));
		},
		storage -> new Dynamic<>(NbtOps.INSTANCE, storage.toCompound())
	);
	private static final PersistentStateManagerAccess PERSISTENT_STATE_MANAGER_ACCESS = new PersistentStateManagerAccess();

	private final Map<BlockPos, StoredPlantEntry> entries = new HashMap<>();

	private PendingPlantStorage() {
	}

	private PendingPlantStorage(Map<BlockPos, StoredPlantEntry> entries) {
		this.entries.putAll(entries);
	}

	static PendingPlantStorage get(ServerWorld world) {
		return PERSISTENT_STATE_MANAGER_ACCESS.get(world);
	}

	void forEachLoaded(ServerWorld world, EntryConsumer consumer) {
		for (Iterator<Map.Entry<BlockPos, StoredPlantEntry>> iterator = entries.entrySet().iterator(); iterator.hasNext();) {
			Map.Entry<BlockPos, StoredPlantEntry> entry = iterator.next();
			StoredPlantEntry stored = entry.getValue();
			BlockState saplingState = stored.decodeSaplingState();
			BlockState belowState = stored.decodeBelowState();
			if (saplingState == null || belowState == null) {
				iterator.remove();
				markDirty();
				continue;
			}
			consumer.accept(entry.getKey(), saplingState, belowState, stored.createdTick());
		}
	}

	void put(BlockPos pos, BlockState saplingState, BlockState belowState, long createdTick) {
		entries.put(pos.toImmutable(), StoredPlantEntry.fromStates(saplingState, belowState, createdTick));
		markDirty();
	}

	void remove(BlockPos pos) {
		if (entries.remove(pos) != null) {
			markDirty();
		}
	}

	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtCompound compound = toCompound();
		copyCompound(compound, nbt);
		return nbt;
	}

	@SuppressWarnings("unused")
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		return writeNbt(nbt);
	}

	private NbtCompound toCompound() {
		NbtCompound root = new NbtCompound();
		NbtList list = new NbtList();
		for (Map.Entry<BlockPos, StoredPlantEntry> entry : entries.entrySet()) {
			NbtCompound element = entry.getValue().toCompound();
			element.putLong("Pos", entry.getKey().asLong());
			list.add(element);
		}
		root.put("Entries", list);
		return root;
	}

	private static PendingPlantStorage fromCompound(NbtCompound nbt) {
		if (nbt == null || !nbt.contains("Entries")) {
			return new PendingPlantStorage();
		}
		NbtElement rawList = nbt.get("Entries");
		if (!(rawList instanceof NbtList list)) {
			return new PendingPlantStorage();
		}
		Map<BlockPos, StoredPlantEntry> map = new HashMap<>();
		for (NbtElement element : list) {
			if (!(element instanceof NbtCompound compound)) {
				continue;
			}
			Optional<Long> optionalPos = NbtBridge.getLong(compound, "Pos");
			if (optionalPos.isEmpty()) {
				continue;
			}
			BlockPos pos = BlockPos.fromLong(optionalPos.get());
			StoredPlantEntry stored = StoredPlantEntry.fromCompound(compound);
			if (stored != null) {
				map.put(pos, stored);
			}
		}
		return new PendingPlantStorage(map);
	}

	private static void copyCompound(NbtCompound source, NbtCompound target) {
		Set<String> keys = source.getKeys();
		for (String key : keys) {
			NbtElement element = source.get(key);
			if (element != null) {
				target.put(key, element.copy());
			}
		}
	}

	interface EntryConsumer {
		void accept(BlockPos pos, BlockState saplingState, BlockState belowState, long createdTick);
	}

	private static final class StoredPlantEntry {
		private static final String SAPLING_ID_KEY = "SaplingId";
		private static final String SAPLING_PROPS_KEY = "SaplingProps";
		private static final String BELOW_ID_KEY = "BelowId";
		private static final String BELOW_PROPS_KEY = "BelowProps";
		private static final String CREATED_TICK_KEY = "CreatedTick";

		private final Identifier saplingId;
		private final Map<String, String> saplingProperties;
		private final Identifier belowId;
		private final Map<String, String> belowProperties;
		private final long createdTick;

		private StoredPlantEntry(
			Identifier saplingId,
			Map<String, String> saplingProperties,
			Identifier belowId,
			Map<String, String> belowProperties,
			long createdTick
		) {
			this.saplingId = saplingId;
			this.saplingProperties = Map.copyOf(saplingProperties);
			this.belowId = belowId;
			this.belowProperties = Map.copyOf(belowProperties);
			this.createdTick = createdTick;
		}

		static StoredPlantEntry fromStates(BlockState saplingState, BlockState belowState, long createdTick) {
			return new StoredPlantEntry(
				Registries.BLOCK.getId(saplingState.getBlock()),
				encodeProperties(saplingState),
				Registries.BLOCK.getId(belowState.getBlock()),
				encodeProperties(belowState),
				createdTick
			);
		}

		static StoredPlantEntry fromCompound(NbtCompound compound) {
			Optional<String> saplingIdRaw = NbtBridge.getString(compound, SAPLING_ID_KEY);
			Optional<String> belowIdRaw = NbtBridge.getString(compound, BELOW_ID_KEY);
			if (saplingIdRaw.isEmpty() || belowIdRaw.isEmpty()) {
				return null;
			}
			Identifier saplingId = Identifier.tryParse(saplingIdRaw.get());
			Identifier belowId = Identifier.tryParse(belowIdRaw.get());
			if (saplingId == null || belowId == null) {
				return null;
			}
			Map<String, String> saplingProperties = decodeProperties(NbtBridge.getCompoundOrEmpty(compound, SAPLING_PROPS_KEY));
			Map<String, String> belowProperties = decodeProperties(NbtBridge.getCompoundOrEmpty(compound, BELOW_PROPS_KEY));
			long createdTick = NbtBridge.getLong(compound, CREATED_TICK_KEY).orElse(0L);
			return new StoredPlantEntry(saplingId, saplingProperties, belowId, belowProperties, createdTick);
		}

		NbtCompound toCompound() {
			NbtCompound compound = new NbtCompound();
			compound.putString(SAPLING_ID_KEY, saplingId.toString());
			compound.put(SAPLING_PROPS_KEY, encodePropertiesNbt(saplingProperties));
			compound.putString(BELOW_ID_KEY, belowId.toString());
			compound.put(BELOW_PROPS_KEY, encodePropertiesNbt(belowProperties));
			compound.putLong(CREATED_TICK_KEY, createdTick);
			return compound;
		}

		BlockState decodeSaplingState() {
			return decodeState(saplingId, saplingProperties);
		}

		BlockState decodeBelowState() {
			return decodeState(belowId, belowProperties);
		}

		long createdTick() {
			return createdTick;
		}

		@SuppressWarnings({ "rawtypes", "unchecked" })
		private static Map<String, String> encodeProperties(BlockState state) {
			Map<String, String> values = new HashMap<>();
			for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
				Property property = entry.getKey();
				values.put(property.getName(), property.name(entry.getValue()));
			}
			return values;
		}

		private static Map<String, String> decodeProperties(NbtCompound compound) {
			if (compound == null || compound.isEmpty()) {
				return Map.of();
			}
			Map<String, String> map = new HashMap<>();
			for (String key : compound.getKeys()) {
				NbtBridge.getString(compound, key).ifPresent(value -> map.put(key, value));
			}
			return map;
		}

		private static NbtCompound encodePropertiesNbt(Map<String, String> properties) {
			NbtCompound compound = new NbtCompound();
			properties.forEach(compound::putString);
			return compound;
		}

		private static BlockState decodeState(Identifier blockId, Map<String, String> properties) {
			Block block = Registries.BLOCK.get(blockId);
			if (!Registries.BLOCK.getId(block).equals(blockId)) {
				return null;
			}
			BlockState result = block.getDefaultState();
			StateManager<Block, BlockState> manager = block.getStateManager();
			for (Map.Entry<String, String> entry : properties.entrySet()) {
				Property<?> property = manager.getProperty(entry.getKey());
				if (property == null) {
					continue;
				}
				Optional<?> parsed = property.parse(entry.getValue());
				if (parsed.isEmpty()) {
					continue;
				}
				result = apply(result, property, parsed.get());
			}
			return result;
		}

		@SuppressWarnings("unchecked")
		private static <T extends Comparable<T>> BlockState apply(BlockState state, Property<?> property, Object value) {
			Property<T> typed = (Property<T>) property;
			return state.with(typed, (T) value);
		}
	}

	private static final class PersistentStateManagerAccess {
		private final Object typeInstance;
		private final Method modernGetOrCreate;
		private final Method legacyGetOrCreate;

		private PersistentStateManagerAccess() {
			Class<?> modernTypeClass = findClass("net.minecraft.world.PersistentStateType");
			if (modernTypeClass != null) {
				this.typeInstance = createModernType(modernTypeClass);
				this.modernGetOrCreate = findMethod(PersistentStateManager.class, "getOrCreate", modernTypeClass);
				this.legacyGetOrCreate = null;
				if (modernGetOrCreate == null) {
					throw new IllegalStateException("Missing modern getOrCreate(PersistentStateType) method");
				}
				modernGetOrCreate.setAccessible(true);
				return;
			}

			Class<?> legacyTypeClass = findClass("net.minecraft.world.PersistentState$Type");
			if (legacyTypeClass != null) {
				this.typeInstance = createLegacyType(legacyTypeClass);
				this.legacyGetOrCreate = findMethod(PersistentStateManager.class, "getOrCreate", legacyTypeClass, String.class);
				this.modernGetOrCreate = null;
				if (legacyGetOrCreate == null) {
					throw new IllegalStateException("Missing legacy getOrCreate(Type, String) method");
				}
				legacyGetOrCreate.setAccessible(true);
				return;
			}

			throw new IllegalStateException("Unsupported PersistentStateManager API");
		}

		PendingPlantStorage get(ServerWorld world) {
			PersistentStateManager manager = world.getPersistentStateManager();
			try {
				if (modernGetOrCreate != null) {
					return (PendingPlantStorage) modernGetOrCreate.invoke(manager, typeInstance);
				}
				return (PendingPlantStorage) legacyGetOrCreate.invoke(manager, typeInstance, STORAGE_ID);
			} catch (IllegalAccessException | InvocationTargetException e) {
				throw new IllegalStateException("Unable to access pending plant data", e);
			}
		}

		private static Class<?> findClass(String name) {
			try {
				return Class.forName(name);
			} catch (ClassNotFoundException ignored) {
				return null;
			}
		}

		private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
			try {
				return owner.getMethod(name, params);
			} catch (NoSuchMethodException ignored) {
				return null;
			}
		}

		private static Object createModernType(Class<?> typeClass) {
			try {
				try {
					Constructor<?> ctor = typeClass.getConstructor(
						String.class,
						Supplier.class,
						Codec.class,
						DataFixTypes.class
					);
					return ctor.newInstance(
						STORAGE_ID,
						(Supplier<PendingPlantStorage>) PendingPlantStorage::new,
						CODEC,
						DataFixTypes.LEVEL
					);
				} catch (NoSuchMethodException ignored) {
					Constructor<?> ctor = typeClass.getConstructor(
						String.class,
						Function.class,
						Function.class,
						DataFixTypes.class
					);
					Function<Object, PendingPlantStorage> constructor = ignoredContext -> new PendingPlantStorage();
					Function<Object, Codec<PendingPlantStorage>> codecFactory = ignoredContext -> CODEC;
					return ctor.newInstance(STORAGE_ID, constructor, codecFactory, DataFixTypes.LEVEL);
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Failed to create PersistentStateType", e);
			}
		}

		private static Object createLegacyType(Class<?> typeClass) {
			try {
				Constructor<?> ctor = typeClass.getConstructor(
					Supplier.class,
					BiFunction.class,
					DataFixTypes.class
				);
				Supplier<PendingPlantStorage> supplier = PendingPlantStorage::new;
				BiFunction<NbtCompound, RegistryWrapper.WrapperLookup, PendingPlantStorage> deserializer =
					(nbt, lookup) -> fromCompound(nbt);
				return ctor.newInstance(supplier, deserializer, DataFixTypes.LEVEL);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Failed to create PersistentState$Type", e);
			}
		}
	}

	private static final class NbtBridge {
		private static final Method GET_LONG = findMethod("getLong", String.class);
		private static final boolean GET_LONG_RETURNS_OPTIONAL = Optional.class.isAssignableFrom(GET_LONG.getReturnType());
		private static final boolean GET_LONG_RETURNS_PRIMITIVE = GET_LONG.getReturnType() == long.class;

		private static final Method GET_STRING = findMethod("getString", String.class);
		private static final boolean GET_STRING_RETURNS_OPTIONAL = Optional.class.isAssignableFrom(GET_STRING.getReturnType());

		private static final Method GET_COMPOUND = findMethod("getCompound", String.class);
		private static final boolean GET_COMPOUND_RETURNS_OPTIONAL = Optional.class.isAssignableFrom(GET_COMPOUND.getReturnType());
		private static final Method GET_COMPOUND_OR_EMPTY = findMethodOrNull("getCompoundOrEmpty", String.class);

		private static Method findMethod(String name, Class<?>... params) {
			try {
				Method method = NbtCompound.class.getMethod(name, params);
				method.setAccessible(true);
				return method;
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Missing NbtCompound method: " + name, e);
			}
		}

		private static Method findMethodOrNull(String name, Class<?>... params) {
			try {
				Method method = NbtCompound.class.getMethod(name, params);
				method.setAccessible(true);
				return method;
			} catch (ReflectiveOperationException e) {
				return null;
			}
		}

		@SuppressWarnings("unchecked")
		private static Optional<Long> getLong(NbtCompound compound, String key) {
			try {
				Object result = GET_LONG.invoke(compound, key);
				if (GET_LONG_RETURNS_OPTIONAL) {
					return (Optional<Long>) result;
				}
				if (result == null) {
					return Optional.empty();
				}
				if (GET_LONG_RETURNS_PRIMITIVE) {
					return Optional.of((Long) result);
				}
				return Optional.ofNullable((Long) result);
			} catch (IllegalAccessException | InvocationTargetException e) {
				throw new IllegalStateException("Failed to read long from NBT", e);
			}
		}

		@SuppressWarnings("unchecked")
		private static Optional<String> getString(NbtCompound compound, String key) {
			try {
				Object result = GET_STRING.invoke(compound, key);
				if (GET_STRING_RETURNS_OPTIONAL) {
					return (Optional<String>) result;
				}
				return Optional.ofNullable((String) result);
			} catch (IllegalAccessException | InvocationTargetException e) {
				throw new IllegalStateException("Failed to read string from NBT", e);
			}
		}

		@SuppressWarnings("unchecked")
		private static NbtCompound getCompoundOrEmpty(NbtCompound compound, String key) {
			try {
				if (GET_COMPOUND_OR_EMPTY != null) {
					Object result = GET_COMPOUND_OR_EMPTY.invoke(compound, key);
					return result instanceof NbtCompound nbt ? nbt : new NbtCompound();
				}
				Object result = GET_COMPOUND.invoke(compound, key);
				if (GET_COMPOUND_RETURNS_OPTIONAL) {
					Optional<NbtCompound> optional = (Optional<NbtCompound>) result;
					return optional.orElseGet(NbtCompound::new);
				}
				return result instanceof NbtCompound nbt ? nbt : new NbtCompound();
			} catch (IllegalAccessException | InvocationTargetException e) {
				throw new IllegalStateException("Failed to read compound from NBT", e);
			}
		}
	}
}
