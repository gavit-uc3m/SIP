package UA;

import mensajesSIP.*;
import Utilities.*;
import java.net.*;
import java.util.*;
import java.io.IOException;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.swing.Timer;



public class UAListener implements Runnable{

	private static DatagramSocket socketEscuchaUA;
	private static DatagramSocket socketEnvioUA;
	private static String IPProxy;
	private static int puertoEscuchaProxy;
	private static byte[] buffer;
	private static boolean looseRouting;
	static boolean esperandoPass = false;
	static String password = null;
	static Timer timer10s = null;



	//Constructor
	UAListener(DatagramSocket sockEscucha, DatagramSocket sockEnvio, String IP, int port) {
		socketEscuchaUA = sockEscucha;
		socketEnvioUA = sockEnvio;
		IPProxy = IP;
		puertoEscuchaProxy = port;
		System.out.println("\nHilo de escucha iniciado correctamente\n");
	}
	
	
	
	@Override
	public void run() {
		while(true) {
			
			buffer = new byte[1000];
			DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
			try {
				socketEscuchaUA.receive(paquete);
			} catch (IOException e) {
				e.printStackTrace();
			}

			String paqueteStr = new String(paquete.getData(), StandardCharsets.UTF_8);

			if (UserAgent.getDebug())
				System.out.println(paqueteStr);
			else
				System.out.println(paqueteStr.split("\n")[0]);

			SIPMessage mensaje = null;
			try {
				mensaje = SIPMessage.parseMessage(paqueteStr);
			} catch (SIPException e) {
				e.printStackTrace();
			}

			
			//Distincion del tipo de mensaje recibido
			if (mensaje instanceof InviteMessage ) {

				InviteMessage mensajeInvite = (InviteMessage) mensaje;

				if (UserAgent.getOcupado())
					enviaBusyHere(mensajeInvite, puertoEscuchaProxy, IPProxy, socketEnvioUA);

				else {

					UserAgent.setEstado("Proceeding");
					setLooseRouting(mensajeInvite.getRecordRoute() != null);
					gestionaInvite(mensajeInvite);

					System.out.println("El usuario " + mensajeInvite.getFromUri().split(":")[1] + " le esta llamando");
					System.out.println("¿Desea descolgar? [si/no]");
					UserAgent.setRecibeLlamada(true);
					timer10s = iniciaTimer10s(mensajeInvite);
				}
			}
			
			else if (mensaje instanceof ProxyAuthenticationMessage) {
				
				ProxyAuthenticationMessage mensajeAutenticacion = (ProxyAuthenticationMessage) mensaje;

				System.out.println("Autenticacion requerida. Introduzca la contraseña:");
				esperandoPass = true;
					
				UserAgent.setCopiaAutenticacion(mensajeAutenticacion);
			}
			
			else if (mensaje instanceof NotFoundMessage) {
				System.out.println("Error. El usuario no se ha autenticado");				
			}
			
			else if (mensaje instanceof RingingMessage) {
				RingingMessage mensajeRinging = (RingingMessage) mensaje;
				setLooseRouting(mensajeRinging.getRecordRoute() != null); 

				UserAgent.setEstado("Proceeding");
			}
			
			else if (mensaje instanceof TryingMessage) {
				UserAgent.setEstado("Proceeding");
			}
			
			else if (mensaje instanceof OKMessage) {
				
				if (!UserAgent.getTerminaLlamada()) {
					
					UserAgent.setCopiaOK((OKMessage) mensaje);
					
					if(UserAgent.getEstado().equals("Calling"))
						UserAgent.setEstado("Terminated");
					if (UserAgent.getEstado().equals("Proceeding"))
						UserAgent.setEstado("Terminated");

					enviaACK(mensaje);
				}
				
				else {
					UserAgent.setTerminaLlamada(false);
					UserAgent.setOcupado(false);
					System.out.println("LLamada terminada.");
				}
				
			}
			
			else if (mensaje instanceof BusyHereMessage || mensaje instanceof RequestTimeoutMessage
					|| mensaje instanceof ServiceUnavailableMessage) {
				
				if(UserAgent.getEstado().equals("Calling"))
					UserAgent.setEstado("Completed");
				if (UserAgent.getEstado().equals("Proceeding"))
					UserAgent.setEstado("Completed");
				
				enviaACK(mensaje);

				UserAgent.setTimer1s(UserAgent.iniciaTimer1s());
				
			}
			
			else if (mensaje instanceof ACKMessage) {
				
				if(UserAgent.getEstado().equals("Completed"))
					UserAgent.setEstado("Terminated");
				
				UserAgent.paraTimer(UserAgent.getTimer1s());
				UserAgent.paraTimer(UserAgent.getTimer200ms());
				
			}
			
			else if (mensaje instanceof ByeMessage) {
				
				ByeMessage mensajeBYE = (ByeMessage) mensaje;
				gestionaBye(mensajeBYE);
				UserAgent.setOcupado(false);
			}
			
			else 
				System.out.println("ERROR. Mensaje recibido de tipo desconocido.");	
		}
	}
	
	
	
	//------------------- FUNCIONES -------------------
	
	
	public static void enviaBusyHere(InviteMessage mensajeInvite, int puertoescuchaProxy, String IPProxy, DatagramSocket socketEnvioUA) {
		
		BusyHereMessage mensaje = new BusyHereMessage();
		
		mensaje.setContentLength(0);
		mensaje.setVias(mensajeInvite.getVias());
		mensaje.setToUri(mensajeInvite.getToUri());
		mensaje.setFromUri(mensajeInvite.getFromUri());
		mensaje.setCallId(mensajeInvite.getCallId());
		mensaje.setcSeqStr(mensajeInvite.getcSeqStr());
		int seqNumber = Integer.parseInt(mensajeInvite.getcSeqNumber())+1;
		mensaje.setcSeqNumber(String.valueOf(seqNumber));

		enviarMensajeSIP(mensaje, IPProxy, puertoescuchaProxy);

	}
	
	static void gestionaInvite( InviteMessage mensajeRecibido) {
		
		UserAgent.setCopiaInvite(mensajeRecibido);
		RingingMessage mensaje = new RingingMessage();
		
		mensaje.setToUri(mensajeRecibido.getToUri());
		mensaje.setFromUri(mensajeRecibido.getFromUri());
		mensaje.setContact(mensajeRecibido.getToUri());
		int seqNumber = Integer.parseInt(mensajeRecibido.getcSeqNumber())+1;
		mensaje.setcSeqNumber(String.valueOf(seqNumber));
		mensaje.setcSeqStr(mensajeRecibido.getcSeqStr());
		mensaje.setCallId(mensajeRecibido.getCallId());
		mensaje.setVias(mensajeRecibido.getVias());		
		try {
			mensaje.setContact(InetAddress.getLocalHost().getHostAddress() + ":" + socketEscuchaUA.getLocalPort());
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		if (getLooseRouting())
			mensaje.setRecordRoute(mensajeRecibido.getRecordRoute());

		String IP = mensajeRecibido.getVias().get(0).split(":")[0];
		enviarMensajeSIP(mensaje, IP, puertoEscuchaProxy);

	}
	
	static void enviarMensajeSIP(SIPMessage mensaje, String IP, int port) {
		
		InetAddress direccionInet = null;
		try {
			direccionInet = InetAddress.getByName(IP);
		} catch (UnknownHostException exceptionInet) {
			exceptionInet.printStackTrace();
		}
		
		byte[] dataBytes = mensaje.toStringMessage().getBytes();
		DatagramPacket paquete = new DatagramPacket(dataBytes, dataBytes.length, direccionInet, port);
		
		try {
			socketEnvioUA.send(paquete);
		} catch (IOException exception) {
			exception.printStackTrace();
		}
	}
	
	public static Timer iniciaTimer10s(InviteMessage mensaje){
		
		Timer timer = new Timer(10000, new ActionListener () { 
			public void actionPerformed(ActionEvent e) {
				System.out.println("Ha expirado el timer, se cancela la llamada.");
				enviaRequestTimeOut(mensaje, puertoEscuchaProxy, IPProxy);
				UserAgent.setEstado("Completed");
				UserAgent.setTimer1s(UserAgent.iniciaTimer1s());
			} 
		}); 
		timer.setRepeats(false);
		timer.start();
		return timer;
	}
	
	public static void enviaRequestTimeOut(InviteMessage mensaje, int puertoescuchaProxy, String IPProxy) {
		
		RequestTimeoutMessage mensajeTimeOut = new RequestTimeoutMessage();
		
		mensajeTimeOut.setCallId(mensaje.getCallId());
		mensajeTimeOut.setFromUri(mensaje.getFromUri());
		mensajeTimeOut.setToUri(mensaje.getToUri());
		mensajeTimeOut.setToName(mensaje.getToName());
		mensajeTimeOut.setFromName(mensaje.getToName());
		mensajeTimeOut.setVias(mensaje.getVias());
		int seqNumber = Integer.parseInt(mensaje.getcSeqNumber());
		mensajeTimeOut.setcSeqNumber(String.valueOf(seqNumber+1));
		mensajeTimeOut.setcSeqStr(mensaje.getcSeqStr());
		
		UserAgent.setTimer200ms(UserAgent.iniciaTimer200ms(mensajeTimeOut, puertoescuchaProxy, IPProxy));
		
		enviarMensajeSIP(mensaje, IPProxy, puertoescuchaProxy);
		
	}
	
	public void enviaACK(SIPMessage mensaje) {
		ACKMessage mensajeACK = new ACKMessage();
		
		String IP = null;
		int puerto = 0;
		if (mensaje instanceof OKMessage) {
			
			OKMessage mensajeOK = (OKMessage) mensaje;
			
			mensajeACK.setVias(mensajeOK.getVias());
			mensajeACK.setFromUri(mensajeOK.getFromUri());
			mensajeACK.setToUri(mensajeOK.getToUri());
			mensajeACK.setDestination(mensajeOK.getToUri());
			mensajeACK.setCallId(mensajeOK.getCallId());
			mensajeACK.setcSeqStr("ACK");
			mensajeACK.setMaxForwards(100);
			int seqNumber = Integer.parseInt(mensajeOK.getcSeqNumber());
			mensajeACK.setcSeqNumber(String.valueOf(seqNumber+1));

			if (getLooseRouting())
				mensajeACK.setRoute(mensajeOK.getRecordRoute());

			if (getLooseRouting()) {
				IP = IPProxy;
				puerto = puertoEscuchaProxy;
			}
			else {
				String[] contact = mensajeOK.getContact().split(":");
				IP = contact[0];
				puerto = Integer.parseInt(contact[1]);
			}
		}
		
		else if (mensaje instanceof BusyHereMessage) {
			
			BusyHereMessage mensajeBusy = (BusyHereMessage) mensaje;
			
			mensajeACK.setVias(mensajeBusy.getVias());
			mensajeACK.setFromUri(mensajeBusy.getFromUri());
			mensajeACK.setToUri(mensajeBusy.getToUri());
			mensajeACK.setDestination(mensajeBusy.getToUri());
			mensajeACK.setCallId(mensajeBusy.getCallId());
			mensajeACK.setcSeqStr("ACK");
			int seqNumber = Integer.parseInt(mensajeBusy.getcSeqNumber())+1;
			mensajeACK.setcSeqNumber(String.valueOf(seqNumber));
			mensajeACK.setMaxForwards(100);
			
			IP = IPProxy;
			puerto = puertoEscuchaProxy;
		}
		
		else if (mensaje instanceof RequestTimeoutMessage) {
			
			RequestTimeoutMessage mensajeTimeout = (RequestTimeoutMessage) mensaje;
			
			mensajeACK.setVias(mensajeTimeout.getVias());
			mensajeACK.setFromUri(mensajeTimeout.getFromUri());
			mensajeACK.setToUri(mensajeTimeout.getToUri());
			mensajeACK.setDestination(mensajeTimeout.getToUri());
			mensajeACK.setCallId(mensajeTimeout.getCallId());
			mensajeACK.setcSeqStr("ACK");
			int seqNumber = Integer.parseInt(mensajeTimeout.getcSeqNumber())+1;
			mensajeACK.setcSeqNumber(String.valueOf(seqNumber));
			mensajeACK.setMaxForwards(100);
			
			IP = IPProxy;
			puerto = puertoEscuchaProxy;
		}
		
		else if (mensaje instanceof ServiceUnavailableMessage) {
			
			ServiceUnavailableMessage mensajeUnavailable = (ServiceUnavailableMessage) mensaje;
			
			mensajeACK.setVias(mensajeUnavailable.getVias());
			mensajeACK.setFromUri(mensajeUnavailable.getFromUri());
			mensajeACK.setToUri(mensajeUnavailable.getToUri());
			mensajeACK.setDestination(mensajeUnavailable.getToUri());
			mensajeACK.setCallId(mensajeUnavailable.getCallId());
			mensajeACK.setcSeqStr("ACK");
			int seqNumber = Integer.parseInt(mensajeUnavailable.getcSeqNumber())+1;
			mensajeACK.setcSeqNumber(String.valueOf(seqNumber));
			mensajeACK.setMaxForwards(100);
			
			IP = IPProxy;
			puerto = puertoEscuchaProxy;
		}

		enviarMensajeSIP(mensajeACK, IP, puerto);
	}
	
	
	public static void gestionaBye(ByeMessage mensaje) {
		
		OKMessage mensajeOK = new OKMessage();

		mensajeOK.setVias(mensaje.getVias());
		mensajeOK.setFromUri(mensaje.getFromUri());
		mensajeOK.setToUri(mensaje.getToUri());
		try {
			mensajeOK.setContact(InetAddress.getLocalHost().getHostAddress() + ":" + UserAgent.getPuertoEscuchaUA());
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		mensajeOK.setRoute(mensaje.getRoute());
		mensajeOK.setcSeqStr(mensaje.getcSeqStr());
		int seqNumber = Integer.parseInt(mensaje.getcSeqNumber())+1;
		mensajeOK.setcSeqNumber(String.valueOf(seqNumber));
		mensajeOK.setContentLength(mensaje.getContentLength());
		mensajeOK.setCallId(mensaje.getCallId());
		mensajeOK.setSdp(UserAgent.getCopiaInvite().getSdp());

		String IP;
		int puerto;
		if (getLooseRouting()) {
			IP = IPProxy;
			puerto = puertoEscuchaProxy;
		}
		else {
			String destino;
			if ( UserAgent.getCopiaInvite().getFromUri().equals(mensaje.getFromUri())) {
				destino = UserAgent.getCopiaInvite().getContact();		
			}
			else {
				destino = UserAgent.getCopiaOK().getContact();
			}
			
			IP = destino.split(":")[0];
			puerto = Integer.parseInt(destino.split(":")[1]);
		}

		enviarMensajeSIP(mensajeOK, IP, puerto);
	}


	public static void autenticaUsuario(ProxyAuthenticationMessage mensaje, String IPProxy, int puertoEscuchaProxy, String pass) {

		String usuario = mensaje.getFromUri().split(":")[1];
		String llamado = mensaje.getToUri().split(":")[1];
		int puertoEscuchaUsuario = Integer.parseInt(mensaje.getVias().get(0).split(":")[1]);

		InviteMessage mensajeInvite = new InviteMessage();
		mensajeInvite = creaInvite(usuario, llamado, IPProxy, puertoEscuchaProxy, puertoEscuchaUsuario, mensaje.getCallId(), Integer.parseInt(mensaje.getcSeqNumber()));

		//Autenticacion
		String hash = mensaje.getproxyAuthenticate() + pass;
		MessageDigest md5 = null;
		try {
			md5 = MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e1) {
			e1.printStackTrace();
		}
		byte[] Bytes = hash.getBytes();
		int nread = Bytes.length;
		md5.update(Bytes, 0, nread);
		byte[] mdbytes = md5.digest();

		//convert the byte to hex format
		StringBuffer md5Hexa = new StringBuffer();
		for (int i = 0; i < mdbytes.length; i++) {
			md5Hexa.append(Integer.toString((mdbytes[i] & 0xff) + 0x100, 16).substring(1));
		}

		mensajeInvite.setProxyAuthentication(md5Hexa.toString());

		UserAgent.setCopiaInvite(mensajeInvite);
		enviarMensajeSIP(mensajeInvite, IPProxy, puertoEscuchaProxy);	
	}

	
	
	public static InviteMessage creaInvite(String usuario, String llamado, String IPProxy, int puertoescuchaProxy, int puertoescuchaUsuario, String callId, int cSeqNumber)  {
		
		InviteMessage mensaje = new InviteMessage();
		String contact = null;
		try {
			contact = InetAddress.getLocalHost().getHostAddress() + ":" + puertoescuchaUsuario;
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}

		mensaje.setDestination("sip:" + llamado);
		ArrayList<String> viaUA = new ArrayList<String>();
		viaUA.add(contact);
		mensaje.setVias(viaUA);
		mensaje.setToUri("sip:" + llamado);
		mensaje.setFromUri("sip:" + usuario);
		mensaje.setMaxForwards(100);
		mensaje.setContact(contact);
		mensaje.setContentLength(0);
		mensaje.setcSeqStr("INVITE");
		mensaje.setcSeqNumber(String.valueOf(cSeqNumber+1));
		mensaje.setCallId(callId);
		
		// Contenido SDP
		SDPMessage sdp = new SDPMessage();
		try {
			sdp.setIp(InetAddress.getLocalHost().getHostAddress() );
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		sdp.setPort(puertoescuchaUsuario);
		
		ArrayList<Integer> opciones = new ArrayList<Integer>();
		opciones.add(96);
		opciones.add(97);
		opciones.add(98);
		sdp.setOptions(opciones);
		
		mensaje.setSdp(sdp);
		
		return mensaje;
	}
	
	
	// ------------ Getters y setters ----------------
	
	public void setLooseRouting(boolean recordRoute) {
		looseRouting = recordRoute;
	}
	
	public static boolean getLooseRouting() {
		return looseRouting;
	}
	
	public static boolean getEsperandoPass(){
		return esperandoPass;
	}
	
	public static void setEsperandoPass(boolean esperando){
		esperandoPass = esperando;
	}
	
	public static void setPass(String pass){
		password = pass;
	}
	
	public static Timer getTimer10s() {
		return timer10s;
	}

}
