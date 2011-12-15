/*
 * This file is part of SpoutcraftAPI (http://wiki.getspout.org/).
 * 
 * SpoutcraftAPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SpoutcraftAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.getspout.commons.command;

import org.getspout.commons.plugin.Plugin;

public final class AddonCommand implements CommandExecutor {
	private final Plugin owningAddon;
	private CommandExecutor executor;

	protected AddonCommand(String name, Plugin owner) {
		this.executor = owner;
		this.owningAddon = owner;
	}

	@Override
	public boolean processCommand(CommandSource source, Command command, Enum<?> commandEnum, String[] args, int baseIndex) {
		
		boolean success = false;

		if (!this.owningAddon.isEnabled()) {
			return false;
		}
		try {
			success = this.executor.processCommand(source, command, commandEnum, args, baseIndex);
		} catch (Throwable ex) {
			throw new CommandException("Unhandled exception executing command '" + command.getPreferredName() + "' in plugin " + this.owningAddon.getDescription().getFullName(), ex);
		}

		if ((!success) && (command.getUsageMessage() != null)) {
			source.sendMessage(command.getUsageMessage());
		}

		return success;
	}

	public Plugin getAddon() {
		return this.owningAddon;
	}

}
