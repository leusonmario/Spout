/*
 * This file is part of Spout.
 *
 * Copyright (c) 2011-2012, Spout LLC <http://www.spout.org/>
 * Spout is licensed under the Spout License Version 1.
 *
 * Spout is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the Spout License Version 1.
 *
 * Spout is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the Spout License Version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://spout.in/licensev1> for the full license, including
 * the MIT license.
 */
package org.spout.engine.protocol.builtin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.spout.api.Spout;

import org.spout.api.entity.Entity;
import org.spout.api.geo.LoadOption;
import org.spout.api.geo.World;
import org.spout.api.geo.cuboid.Chunk;
import org.spout.api.geo.cuboid.ChunkSnapshot;
import org.spout.api.geo.discrete.Point;
import org.spout.api.geo.discrete.Transform;
import org.spout.api.material.BlockMaterial;
import org.spout.api.math.IntVector3;
import org.spout.api.math.Quaternion;
import org.spout.api.math.Vector3;
import org.spout.api.protocol.EntityProtocol;
import org.spout.api.protocol.Message;
import org.spout.api.protocol.ServerNetworkSynchronizer;
import org.spout.api.protocol.Session;
import org.spout.engine.component.entity.SpoutSceneComponent;
import org.spout.engine.entity.SpoutPlayer;
import org.spout.engine.protocol.builtin.message.BlockUpdateMessage;
import org.spout.engine.protocol.builtin.message.ChunkDataMessage;
import org.spout.engine.protocol.builtin.message.UpdateEntityMessage;
import org.spout.engine.protocol.builtin.message.WorldChangeMessage;

public class SpoutServerNetworkSynchronizer extends ServerNetworkSynchronizer {
	private Point lastChunkCheck =  Point.invalid;

	// Base points used so as not to load chunks unnecessarily
	private final Set<Point> chunkInitQueue = new LinkedHashSet<Point>();
	private final Set<Point> priorityChunkSendQueue = new LinkedHashSet<Point>();
	private final Set<Point> chunkSendQueue = new LinkedHashSet<Point>();
	private final Set<Point> chunkFreeQueue = new LinkedHashSet<Point>();

	/** Chunks that have initialized on the client. May also have chunks that have been sent. */
	private final Set<Point> initializedChunks = new LinkedHashSet<Point>();
	/** Chunks that have been sent to the client */
	private final Set<Point> activeChunks = new LinkedHashSet<Point>();

	private volatile boolean worldChanged = true;
	private final LinkedHashSet<Chunk> observed = new LinkedHashSet<Chunk>();
	/** Includes chunks that need to be observed. When observation is successfully attained or no longer wanted, point is removed */
	private final Set<Point> chunksToObserve = new LinkedHashSet<Point>();
	private boolean sync = false;

	
	protected int tickCounter = 0;

	public SpoutServerNetworkSynchronizer(Session session) {
		super(session, 3);
	}

	@Override
	public void forceRespawn() {
		worldChanged = true;
	}

	@Override
	protected void clearObservers() {
		super.clearObservers();
		chunksToObserve.clear();
		for (Point p : initializedChunks) {
			removeObserver(p);
		}
	}
	
	@Override
	public void forceSync() {
		sync = true;
	}

	/**
	 * Called just before the pre-snapshot stage.<br>
	 * This stage can make changes but they should be checked to make sure they
	 * are non-conflicting.
	 */
	@Override
	public void finalizeTick() {
		super.finalizeTick();
		tickCounter++;

		//TODO: update chunk lists?
		final int prevViewDistance = ((SpoutPlayer) player).getPrevViewDistance();
		// TODO: protocol - maybe we should clarify that this is a live value
		final int currentViewDistance = player.getViewDistance() >> Chunk.BLOCKS.BITS;

		final Point lastPosition = ((SpoutSceneComponent) player.getScene()).getTransform().getPosition();
		final Point currentPosition = ((SpoutSceneComponent) player.getScene()).getTransformLive().getPosition();
		
		if (lastPosition == null || (currentPosition != null && lastPosition.getWorld() != currentPosition.getWorld())) {
			clearObservers();
			worldChanged = true;
		}
		if (currentPosition != null) {
			if (prevViewDistance != currentViewDistance || worldChanged || (!currentPosition.equals(lastChunkCheck) && currentPosition.getManhattanDistance(lastChunkCheck) > (Chunk.BLOCKS.SIZE / 2))) {
				checkChunkUpdates(currentPosition);
				lastChunkCheck = currentPosition;
			}


		}

		if (!worldChanged) {
			for (Point p : chunkFreeQueue) {
				if (initializedChunks.contains(p)) {
					removeObserver(p);
				}
			}

			for (Point p : chunkInitQueue) {
				if (!initializedChunks.contains(p)) {
					observe(p);
				}
			}

			checkObserverUpdateQueue();
		}

	}
	
	
	/**
	 * Resets all chunk stores for the client.  This method is only called during the pre-snapshot part of the tick.
	 */
	protected void resetChunks() {
		priorityChunkSendQueue.clear();
		chunkSendQueue.clear();
		chunkFreeQueue.clear();
		chunkInitQueue.clear();
		activeChunks.clear();
		initializedChunks.clear();
		lastChunkCheck = Point.invalid;
		synchronizedEntities.clear();
	}
	
	private int chunksSent = 0;
	private Set<Point> unsendable = new HashSet<Point>();

	@Override
	public void preSnapshot() {
		super.preSnapshot();
		if (worldChanged) {
			Point ep = player.getScene().getPosition();
			resetChunks();
			worldChanged(ep.getWorld());
			worldChanged = false;
		} else {
			unsendable.clear();

			for (Point p : chunkFreeQueue) {
				if (initializedChunks.remove(p)) {
					freeChunk(p);
					activeChunks.remove(p);
				}
			}

			chunkFreeQueue.clear();

			int modifiedChunksPerTick = (!priorityChunkSendQueue.isEmpty() ? 4 : 1) * CHUNKS_PER_TICK;
			chunksSent = Math.max(0, chunksSent - modifiedChunksPerTick);

			for (Point p : chunkInitQueue) {
				if (initializedChunks.add(p)) {
					initChunk(p);
				}
			}

			chunkInitQueue.clear();

			Iterator<Point> i;

			i = priorityChunkSendQueue.iterator();
			while (i.hasNext() && chunksSent < CHUNKS_PER_TICK) {
				Point p = i.next();
				i = attemptSendChunk(i, priorityChunkSendQueue, p);
			}

			if (!priorityChunkSendQueue.isEmpty()) {
				return;
			}

			if (player.getScene().isTransformDirty() && sync) {
				sendPosition(player.getScene().getPosition(), player.getScene().getRotation());
				sync = false;
			}

			boolean tickTimeRemaining = Spout.getScheduler().getRemainingTickTime() > 0;

			i = chunkSendQueue.iterator();
			while (i.hasNext() && chunksSent < CHUNKS_PER_TICK && tickTimeRemaining) {
				Point p = i.next();
				i = attemptSendChunk(i, chunkSendQueue, p);
				tickTimeRemaining = Spout.getScheduler().getRemainingTickTime() > 0;
			}
		}

	}

	private Iterator<Point> attemptSendChunk(Iterator<Point> i, Iterable<Point> queue, Point p) {
		Chunk c = p.getWorld().getChunkFromBlock(p, LoadOption.LOAD_ONLY);
		if (c == null) {
			unsendable.add(p);
			return i;
		}
		if (unsendable.contains(p)) {
			return i;
		}
		if (canSendChunk(c)) {
			Collection<Chunk> sent = sendChunk(c, true);
			activeChunks.add(c.getBase());
			i.remove();
			if (sent != null) {
				for (Chunk s : sent) {
					Point base = s.getBase();
					boolean removed = priorityChunkSendQueue.remove(base);
					removed |= chunkSendQueue.remove(base);
					if (removed) {
						if (initializedChunks.contains(base)) {
							activeChunks.add(base);
						}
						chunksSent++;
						i = queue.iterator();
					}
				}
			}
			chunksSent++;

		} else {
			unsendable.add(p);
		}
		return i;
	}

	private void checkObserverUpdateQueue() {
		Iterator<Point> i = chunksToObserve.iterator();
		while (i.hasNext()) {
			Point p = i.next();
			if (!chunkInitQueue.contains(p) && !this.initializedChunks.contains(p)) {
				i.remove();
			} else {
				Chunk c = p.getWorld().getChunkFromBlock(p, LoadOption.NO_LOAD);
				if (c != null) {
					observe(c);
					i.remove();
				}
			}
		}
	}

	private void observe(Point p) {
		Chunk c = p.getWorld().getChunkFromBlock(p, LoadOption.NO_LOAD);
		if (c != null) {
			observe(c);
		} else {
			chunksToObserve.add(p);
		}
	}

	private void observe(Chunk c) {
		observed.add(c);
		c.refreshObserver(player);
	}

	private void removeObserver(Point p) {
		Chunk c = p.getWorld().getChunkFromBlock(p, LoadOption.NO_LOAD);
		if (c != null) {
			removeObserver(c);
		}
		chunksToObserve.remove(p);
	}

	private void removeObserver(Chunk c) {
		observed.remove(c);
		c.removeObserver(player);
	}

	private void checkChunkUpdates(Point currentPosition) {

		// Recalculating these
		priorityChunkSendQueue.clear();
		chunkSendQueue.clear();
		chunkFreeQueue.clear();
		chunkInitQueue.clear();

		final World world = currentPosition.getWorld();
		final int bx = (int) currentPosition.getX();
		final int by = (int) currentPosition.getY();
		final int bz = (int) currentPosition.getZ();
		
		final int cx = bx >> Chunk.BLOCKS.BITS;
		final int cy = by >> Chunk.BLOCKS.BITS;
		final int cz = bz >> Chunk.BLOCKS.BITS;

		Point playerChunkBase = Chunk.pointToBase(currentPosition);

		for (Point p : initializedChunks) {
			if (!isInViewVolume(p, playerChunkBase, viewDistance)) {
				chunkFreeQueue.add(p);
			}
		}

		Iterator<IntVector3> itr = getViewableVolume(cx, cy, cz, viewDistance);

		while (itr.hasNext()) {
			IntVector3 v = itr.next();
			Point base = new Point(world, v.getX() << Chunk.BLOCKS.BITS, v.getY() << Chunk.BLOCKS.BITS, v.getZ() << Chunk.BLOCKS.BITS);
			boolean inTargetArea = playerChunkBase.getMaxDistance(base) <= blockMinimumViewDistance;
			if (!activeChunks.contains(base)) {
				if (inTargetArea) {
					priorityChunkSendQueue.add(base);
				} else {
					chunkSendQueue.add(base);
				}
			}
			if (!initializedChunks.contains(base)) {
				chunkInitQueue.add(base);
			}
		}
	}

	/**
	 * Returns a copy of all currently active sent chunks to this player
	 *
	 * @return active chunks
	 */
	@Override
	public Set<Chunk> getActiveChunks() {
		HashSet<Chunk> chunks = new HashSet<Chunk>();
		for (Point p : activeChunks) {
			chunks.add(p.getWorld().getChunkFromBlock(p));
		}
		return chunks;
	}

	@Override
	protected boolean canSendChunk(Chunk c) {
		return true;
	}

	@Override
	public Collection<Chunk> doSendChunk(Chunk c) {
		session.send(new ChunkDataMessage(c.getSnapshot(ChunkSnapshot.SnapshotType.BOTH, ChunkSnapshot.EntityType.NO_ENTITIES, ChunkSnapshot.ExtraData.BIOME_DATA)));
		return Collections.singleton(c);
	}

	@Override
	protected void freeChunk(Point p) {
		session.send(new ChunkDataMessage(p.getChunkX(), p.getChunkY(), p.getChunkZ()));
	}

	@Override
	protected void sendPosition(Point p, Quaternion rot) {
		session.send(new UpdateEntityMessage(player.getId(), new Transform(p, rot, Vector3.ONE), UpdateEntityMessage.UpdateAction.TRANSFORM, getRepositionManager()));
	}

	@Override
	protected void worldChanged(World world) {
		session.send(new WorldChangeMessage(world, session.getPlayer().getScene().getTransform(), world.getData()));
	}

	@Override
	public void updateBlock(Chunk chunk, int x, int y, int z, BlockMaterial material, short data) {
		session.send(new BlockUpdateMessage(chunk.getBlock(x, y, z)));
	}

	@Override
	protected void initChunk(Point p) {
	}

	@Override
	// TODO move to ServerNetworkSynchronizer?
	public void syncEntity(Entity e, Transform liveTransform, boolean spawn, boolean destroy, boolean update) {
		super.syncEntity(e, liveTransform, spawn, destroy, update);
		EntityProtocol protocol = getEntityProtocol(e);
		List<Message> messages = new ArrayList<Message>(3);
		if (destroy) {
			messages.addAll(protocol.getDestroyMessages(e));
		}
		if (spawn) {
			messages.addAll(protocol.getSpawnMessages(e, getRepositionManager()));
		}
		if (update) {
			// TODO - might be worth adding force support
			messages.addAll(protocol.getUpdateMessages(e, liveTransform, getRepositionManager(), true));
		}
		for (Message message : messages) {
			this.session.send(message);
		}
	}

	private EntityProtocol getEntityProtocol(Entity entity) {
		EntityProtocol protocol = entity.getNetwork().getEntityProtocol(SpoutProtocol.ENTITY_PROTOCOL_ID);
		if (protocol == null) {
			entity.getNetwork().setEntityProtocol(SpoutProtocol.ENTITY_PROTOCOL_ID, SpoutEntityProtocol.INSTANCE);
			protocol = SpoutEntityProtocol.INSTANCE;
		}
		return protocol;
	}
}
