package org.getspout.server.io.entity.monsters;

import org.getspout.server.entity.monsters.SpoutMonster;

public abstract class FlyingStore<T extends SpoutMonster> extends MonsterStore<T> {
    
    public FlyingStore(Class<T> clazz, String id) {
        super(clazz, id);
    }
}