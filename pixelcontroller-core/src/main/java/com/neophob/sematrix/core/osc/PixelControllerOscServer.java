/**
 * Copyright (C) 2011-2013 Michael Vogt <michu@neophob.com>
 *
 * This file is part of PixelController.
 *
 * PixelController is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PixelController is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PixelController.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.neophob.sematrix.core.osc;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Observable;
import java.util.Observer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;

import com.neophob.sematrix.core.api.PixelController;
import com.neophob.sematrix.core.jmx.PacketAndBytesStatictics;
import com.neophob.sematrix.core.listener.MessageProcessor;
import com.neophob.sematrix.core.properties.CommandGroup;
import com.neophob.sematrix.core.properties.ValidCommands;
import com.neophob.sematrix.osc.client.OscClientException;
import com.neophob.sematrix.osc.client.PixOscClient;
import com.neophob.sematrix.osc.client.impl.OscClientFactory;
import com.neophob.sematrix.osc.model.OscMessage;
import com.neophob.sematrix.osc.server.OscServerException;
import com.neophob.sematrix.osc.server.PixOscServer;
import com.neophob.sematrix.osc.server.impl.OscServerFactory;

/**
 * OSC Server, implements Observer which is used for two use cases:
 * 
 *  1) OSC Message Received - dispatch it
 *  2) VisualState changed - send to remote client
 * 
 * @author michu
 *
 */
public class PixelControllerOscServer implements Observer, PacketAndBytesStatictics {

	/** The log. */
	private static transient final Logger LOG = Logger.getLogger(PixelControllerOscServer.class.getName());

	//size of recieving buffer, should fit a whole image buffer
	private transient static final int REPLY_PACKET_BUFFERSIZE = 32*1024;

	private PixOscServer oscServer;
	private transient PixOscClient oscClient;

	private transient OscReplyManager replyManager;

	/**
	 * 
	 * @param listeningPort
	 * @throws OscServerException 
	 */
	public PixelControllerOscServer(PixelController pixelController, int listeningPort) throws OscServerException {
		if (listeningPort<1) {
			LOG.log(Level.INFO, "Configured Port {0}, OSC Server disabled", new Object[] { listeningPort });
			return;
		}

		LOG.log(Level.INFO,	"Start OSC Server at port {0}", new Object[] { listeningPort });		
		this.oscServer = OscServerFactory.createServerUdp(this, listeningPort, REPLY_PACKET_BUFFERSIZE);
		this.replyManager = new OscReplyManager(pixelController, this);
	}

	/**
	 * 
	 */
	public void startServer() {
		oscServer.startServer();
	}


	public void handleOscMessage(OscMessage oscIn) {
		//sanity check
		if (StringUtils.isBlank(oscIn.getPattern())) {
			LOG.log(Level.INFO,	"Ignore empty OSC message...");
			return;
		}

		String pattern = oscIn.getPattern();

		ValidCommands command;		
		try {
			command = ValidCommands.valueOf(pattern);
		} catch (Exception e) {
			LOG.log(Level.WARNING, "Unknown message: "+pattern, e);
			return;			
		}

		String[] msg = new String[1+command.getNrOfParams()];
		msg[0] = pattern;

		if (oscIn.getBlob()==null && command.getNrOfParams()>0 &&
				command.getNrOfParams()!=oscIn.getArgs().length) {
			String args = oscIn.getArgs()==null ? "null" : ""+oscIn.getArgs().length; 
			LOG.log(Level.WARNING, "Parameter count missmatch, expected: {0} available: {1}. Command: {2}.",
					new String[]{""+command.getNrOfParams(), ""+args, command.toString()});
			return;
		}

		//ignore nr of parameter for osc generator
		if (command != ValidCommands.OSC_GENERATOR1 && command != ValidCommands.OSC_GENERATOR2) {			
			for (int i=0; i<command.getNrOfParams(); i++) {
				msg[1+i] = oscIn.getArgs()[i];
			}			
		}

		if (command.getGroup() != CommandGroup.INTERNAL) {
			LOG.log(Level.INFO, "Recieved OSC message: {0}", msg);
			MessageProcessor.INSTANCE.processMsg(msg, true, oscIn.getBlob());					
		} else {
			LOG.log(Level.FINE, "Recieved internal OSC message: {0}", msg);
			try {
				this.verifyOscClient(oscIn.getSocketAddress());
				this.replyManager.sendReply(oscClient, msg);
			} catch (OscClientException e) {
				LOG.log(Level.WARNING, "Failed to send OSC Message!", e);
			}
		}
	}

	/**
	 * message from visual state, something changed. if a remote client is registered
	 * we send the update to the remote client
	 * 
	 * @param guiState
	 */
	public void handleRemoteObserverMessage(ArrayList<String> guiState) {
		System.out.println("SEND TO REMOTE: "+guiState);
		if (oscClient!=null) {
			String[] msg = new String[1+guiState.size()];
			int ofs=0;
			msg[ofs] = ValidCommands.GET_GUISTATE.toString();			
			for (String s: guiState){
				msg[ofs++] = s;
			}

			try {
				this.replyManager.sendReply(oscClient, msg);
			} catch (OscClientException e) {
				LOG.log(Level.SEVERE, "Failed to send observer message!", e);
			}			
		}
	}


	private synchronized void verifyOscClient(SocketAddress socket) throws OscClientException {
		InetSocketAddress remote = (InetSocketAddress)socket;
		boolean initNeeded = false;

		if (oscClient == null) {
			initNeeded = true;
		} else if (oscClient.getTargetIp() != remote.getAddress().getHostName()) {
			//TODO Verify port nr
			initNeeded = true;
		}

		if (initNeeded) {			
			//TODO make configurable
			oscClient = OscClientFactory.createClientTcp(remote.getAddress().getHostName(), 9875, REPLY_PACKET_BUFFERSIZE);			
		}
	}

	@Override
	public void update(Observable o, Object arg) {
		try {
			if (arg instanceof OscMessage) {
				OscMessage msg = (OscMessage) arg;
				handleOscMessage(msg);
			} else if (arg instanceof ArrayList) {
				ArrayList<String> msg = (ArrayList) arg;
				handleRemoteObserverMessage(msg);
			} else {
				LOG.log(Level.WARNING, "Ignored notification of unknown type: "+arg);
			}			
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Failed to parse observer message!", e);
		}
	}

	/* (non-Javadoc)
	 * @see com.neophob.sematrix.core.jmx.PacketAndBytesStatictics#getPacketCounter()
	 */
	@Override
	public int getPacketCounter() {
		return oscServer.getPacketCounter();
	}

	/* (non-Javadoc)
	 * @see com.neophob.sematrix.core.jmx.PacketAndBytesStatictics#getBytesRecieved()
	 */
	@Override
	public long getBytesRecieved() {
		return oscServer.getBytesRecieved();
	}	

}
