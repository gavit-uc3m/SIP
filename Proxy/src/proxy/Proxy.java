package proxy;

import mensajesSIP.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.*;
import java.awt.event.*;
import java.security.*;
import javax.swing.Timer;



public class Proxy {

	static int puertoescuchaProxy;
	static boolean loose_routing;
	static boolean debug;
	static DatagramSocket socket;
	private static String proxyIP;
	static String usuario;
	static boolean proxyOcupado = false;
	static String estadoLlamante;
	static String estadoLlamado;
	private static int contador = 0;
	private static Timer timer200ms;
	static byte[]  randombuf = new byte[50];
	static String codigo = null;



	static ArrayList<Usuario> usuariosPermitidos = new ArrayList<Usuario>();
	static {
		usuariosPermitidos.add(new Usuario("alice", "alice"));
		usuariosPermitidos.add(new Usuario("boss", "boss"));
		usuariosPermitidos.add(new Usuario("charlie", "ch@rlie"));
	}
	static ArrayList<Usuario> usuariosRegistrados = new ArrayList<Usuario>();

	
	
	// ---------- MAIN -------------
	public static void main(String args[]) {
		
		System.out.println("\nProxy iniciado correctamente\n");
		
		//Crear codigo para autenticacion
		codigo = crearContraseñaProxy();

		// Captura de argumentos
		if (capturaArgs(args) == -1)
			return;

		try {

			// Creacion de socket
			InetAddress IPProxy = null;
			try {
				IPProxy = InetAddress.getLocalHost();
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
			proxyIP = IPProxy.toString().split("/")[1];
			try {
				socket = new DatagramSocket(puertoescuchaProxy,InetAddress.getByName(proxyIP));
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}

			while(true) {

				SIPMessage mensaje = mensajeNuevo (socket);

				//Se ha recibido un mensaje tipo Register
				if (mensaje instanceof RegisterMessage ) {
					
					RegisterMessage mensajeRegistro = (RegisterMessage) mensaje;
					usuario = mensajeRegistro.getFromUri().split("@")[0];
					boolean checkUser = comprobarUsuario(usuario.split(":")[1]);


					if(checkUser == true){
						gestionaRegister(mensajeRegistro, socket, checkUser);
					}
					else {
						System.out.println("\nERROR. Usuario no permitido\n");
						gestionaRegister(mensajeRegistro, socket, checkUser);
					}
				}

				//Se ha recibido un mensaje tipo Invite
				else if (mensaje instanceof InviteMessage ) {

					InviteMessage mensajeInvite = (InviteMessage) mensaje;

					if (proxyOcupado) {
						enviaServiceUnavailable(mensajeInvite);
					}
					else {
						gestionaInvite(mensajeInvite, socket);
						estadoLlamante = "Calling";
						System.out.println("El estado actual del llamante es: " + estadoLlamante + "\n");
					}
				}

				//Se ha recibido un mensaje tipo ACK
				else if (mensaje instanceof ACKMessage ) {

					ACKMessage mensajeACK = (ACKMessage) mensaje;

					if (mensajeACK.getRoute() != null)
						gestionaMensajeACK(mensajeACK);

					//Sino, no se hace nada

					estadoLlamante = "Completed";
					System.out.println("El estado actual del llamante es: " + estadoLlamante + "\n");

					paraTimer(timer200ms);
				}

				//Se ha recibido un mensaje tipo Ringing
				else if (mensaje instanceof RingingMessage ) {

					RingingMessage mensajeRinging = (RingingMessage) mensaje;

					gestionaMensajeRinging(mensajeRinging);
					estadoLlamado = "Proceeding";
					estadoLlamante = "Proceeding";
					System.out.println("El estado actual del llamado es: " + estadoLlamado + "\n");
					System.out.println("El estado actual del llamante es: " + estadoLlamante + "\n");

				}

				//Se ha recibido un mensaje tipo 200OK
				else if (mensaje instanceof OKMessage ) {

					OKMessage mensaje200OK = (OKMessage) mensaje;

					gestionaMensaje200OK(mensaje200OK);
					estadoLlamado = "Terminated";
					estadoLlamante = "Terminated";
					System.out.println("El estado actual del llamado es: " + estadoLlamado + "\n");
					System.out.println("El estado actual del llamante es: " + estadoLlamante + "\n");
				}

				//Se ha recibido un mensaje tipo BYE
				else if (mensaje instanceof ByeMessage ) {

					ByeMessage mensajeBYE = (ByeMessage) mensaje;
					gestionaMensajeBYE(mensajeBYE);
				}

				//Se ha recibido un mensaje tipo BusyHere
				else if (mensaje instanceof BusyHereMessage ) {

					BusyHereMessage mensajeBusyHere = (BusyHereMessage) mensaje;
					gestionaBusyHere(mensajeBusyHere);
					estadoLlamado = "Completed";
					System.out.println("El estado actual del llamado es: " + estadoLlamado + "\n");

					String usuario = mensajeBusyHere.getFromUri();

					String IPUsuario = null;
					int puertoUsuario = 0;

					for (int i = 0 ; i<usuariosRegistrados.size(); i++) {
						if (usuariosRegistrados.get(i).getNombreUsuario().equals(usuario) ) {
							IPUsuario = usuariosRegistrados.get(i).getIP();
							puertoUsuario = Integer.parseInt(usuariosRegistrados.get(i).getPuerto());
						}
					}

					timer200ms = iniciaTimer200ms((SIPMessage)mensajeBusyHere, puertoUsuario, IPUsuario);
				}

				//Se ha recibido un mensaje tipo RequestTimeout
				else if (mensaje instanceof RequestTimeoutMessage ) {

					RequestTimeoutMessage mensajeTimeout = (RequestTimeoutMessage) mensaje;
					gestionaMensajeTimeout(mensajeTimeout);
					estadoLlamado = "Completed";
					System.out.println("El estado actual del llamado es: " + estadoLlamado + "\n");

					String usuario = mensajeTimeout.getFromUri();

					String IPUsuario = null;
					int puertoUsuario = 0;

					for (int i = 0 ; i<usuariosRegistrados.size(); i++) {
						if (usuariosRegistrados.get(i).getNombreUsuario().equals(usuario) ) {
							IPUsuario = usuariosRegistrados.get(i).getIP();
							puertoUsuario = Integer.parseInt(usuariosRegistrados.get(i).getPuerto());
						}
					}

					timer200ms = iniciaTimer200ms((SIPMessage)mensajeTimeout, puertoUsuario, IPUsuario);
				}

			}
		} catch (SocketException e) {
			System.out.println("\nSocket: " + e.getMessage());
		} 
	}


	// ----------------- FIN MAIN -------------------
	

	public static int capturaArgs(String[] args) {

		if (args.length > 3) {
			System.out.println("\nError. demasiados argumentos");
			return -1;
		}
		
		switch(args.length) {

		case 1: 
			puertoescuchaProxy = Integer.parseInt(args[0]);
			return 0;

		case 2 : 
			puertoescuchaProxy = Integer.parseInt(args[0]);

			if (args[1].equals("true")) {
				loose_routing = true;
			}
			else if (args[1].equals("false")) {
				loose_routing = false;
			}
			else {
				System.out.println("\nError. Parametro loose-routing incorrecto.");
				return -1;
			}
			return 0;

		case 3:
			puertoescuchaProxy = Integer.parseInt(args[0]);

			if (args[1].equals("true")) {
				loose_routing = true;
			}
			else if (args[1].equals("false")) {
				loose_routing = false;
			}
			else {
				System.out.println("\nError. Parametro loose-routing incorrecto.");
				return -1;
			}

			if (args[2].equals("true")) {
				debug = true;
			}
			else if (args[2].equals("false")) {
				debug = false;
			}
			else {
				System.out.println("\nError. Parametro debug incorrecto.");
				return -1;
			}
			return 0;

		default: 
			puertoescuchaProxy = 5060;
			loose_routing = false;
			debug = false;
			return 0;
		}
	}


	public static SIPMessage mensajeNuevo (DatagramSocket socket) {

		byte[] buffer = new byte[2000];
		DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);

		try {
			socket.receive(paquete);
		} catch (IOException exception) {
			exception.printStackTrace();
		}
		System.out.println("Nuevo mensaje recibido\n");

		String paqueteString = new String(paquete.getData(), StandardCharsets.UTF_8);
		SIPMessage mensaje = null;
		try {
			mensaje = SIPMessage.parseMessage(paqueteString);

			if (debug) {
				System.out.println("\n+------------------------------------+");
				System.out.println(paqueteString);
				System.out.println("+------------------------------------+\n");
			}
			else
				System.out.println("\n" + paqueteString.split("\n")[0] + "\n");
		} catch (SIPException e) {
			e.printStackTrace();
		}

		return mensaje;
	}

	public static void gestionaRegister(RegisterMessage mensajeRegistro, DatagramSocket socket,
			boolean permitido) {

		String[] contact = mensajeRegistro.getContact().split(":");
		String ipUsuario = contact[0]; //direccion ip local
		String portUsuario = contact[1];
		
	
		String nombreUsuario = mensajeRegistro.getFromUri().split(":")[1];
		String usuario = nombreUsuario.split("@")[0];

		ArrayList<String> viaUA = new ArrayList<String>();
		viaUA = mensajeRegistro.getVias();
		String IP = viaUA.get(0).split(":")[0];
		String port = viaUA.get(0).split(":")[1];
		
		if(permitido) {

			System.out.println("Usuario permitido, envio de mensaje 200OK.\n");
			Usuario usuarioRegistrado = new Usuario(usuario, portUsuario, ipUsuario);
			usuariosRegistrados.add(usuarioRegistrado);

			OKMessage mensaje200OK = creaMensaje200OK(mensajeRegistro, usuario, IP, port);
			
			byte[] dataBytes = mensaje200OK.toStringMessage().getBytes();
			DatagramPacket paquete_send = null;
			try {
				paquete_send = new DatagramPacket(dataBytes, dataBytes.length, 
						InetAddress.getByName(ipUsuario), Integer.parseInt(portUsuario));
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}

			try {
				socket.send(paquete_send);
			} catch (IOException exception) {
				exception.printStackTrace();
			}
			System.out.println("\n200OK del Register enviado\n");

			//enviarMensajeSIP((SIPMessage)mensaje200OK, IP, Integer.parseInt(port));
		}

		else {

			NotFoundMessage mensaje404 = creaMensaje404(usuario, usuario, port, mensajeRegistro.getCallId(),
					Integer.parseInt(mensajeRegistro.getcSeqNumber()));

			enviarMensajeSIP((SIPMessage)mensaje404, IP, Integer.parseInt(port));
		}
	}

	public static boolean comprobarUsuario(String usuario) {

		boolean permitido = false;
		for (int i=0 ; i<usuariosPermitidos.size(); i++) {
			if (usuariosPermitidos.get(i).getNombreUsuario().equals(usuario) ) {
				permitido = true;
			}
		}

		return permitido;
	}

	public static OKMessage creaMensaje200OK(RegisterMessage mensajeRegistro, String usuario, String IP, String port) {

		OKMessage mensajeOK = new OKMessage();

		ArrayList<String> viaUA = new ArrayList<String>();
		viaUA.add(IP + ":" + port);
		mensajeOK.setVias(viaUA);
		mensajeOK.setToUri(mensajeRegistro.getToUri());
		mensajeOK.setFromUri(mensajeRegistro.getFromUri());
		mensajeOK.setCallId(mensajeRegistro.getCallId());
		mensajeOK.setcSeqStr("REGISTER");
		
		mensajeOK.setcSeqNumber(Integer.toString(Integer.parseInt(mensajeRegistro.getcSeqNumber())+1));
		try {
			mensajeOK.setContact(InetAddress.getLocalHost().getHostAddress() + ":" + port);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		mensajeOK.setContentLength(0);

		return mensajeOK;
	}


	public static NotFoundMessage creaMensaje404(String caller, String called, String port, String callId, 
			int cSeqNumber) {

		NotFoundMessage mensaje404 = new NotFoundMessage();

		ArrayList<String> viaUA = new ArrayList<String>();
		try {
			viaUA.add(InetAddress.getLocalHost().getHostName() + ":" + port);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}

		mensaje404.setVias(viaUA);
		mensaje404.setToUri(caller);
		mensaje404.setFromUri(called);
		mensaje404.setCallId(callId);
		mensaje404.setcSeqStr("INVITE");
		mensaje404.setcSeqNumber(Integer.toString(cSeqNumber+1));
		try {
			mensaje404.setContact(usuario + "@" + InetAddress.getLocalHost().getHostAddress() + ":" + port);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		mensaje404.setContentLength(0);

		return mensaje404;
	}


	public static void enviaServiceUnavailable(InviteMessage mensajeInvite) {

		ServiceUnavailableMessage mensajeServiceUnavailable = new ServiceUnavailableMessage();

		mensajeServiceUnavailable.setCallId(mensajeInvite.getCallId());
		mensajeServiceUnavailable.setFromUri(mensajeInvite.getFromUri());
		mensajeServiceUnavailable.setToUri(mensajeInvite.getToUri());
		mensajeServiceUnavailable.setcSeqStr(mensajeInvite.getcSeqStr());
		int seqNumber = Integer.parseInt(mensajeInvite.getcSeqNumber());
		mensajeServiceUnavailable.setcSeqNumber(Integer.toString(seqNumber+1));
		mensajeServiceUnavailable.setVias(mensajeInvite.getVias());

		String usuario = mensajeServiceUnavailable.getFromUri();

		String IPusuario = null;
		int puertoUsuario = 0;

		for (int i = 0 ; i<usuariosRegistrados.size(); i++) {
			if (usuariosRegistrados.get(i).getNombreUsuario().equals(usuario) ) {
				IPusuario = usuariosRegistrados.get(i).getIP();
				puertoUsuario = Integer.parseInt(usuariosRegistrados.get(i).getPuerto());
			}
		}

		enviarMensajeSIP(mensajeServiceUnavailable, IPusuario, puertoUsuario);

		timer200ms = iniciaTimer200ms(mensajeServiceUnavailable, puertoUsuario, IPusuario);
	}


	public static void gestionaInvite(InviteMessage mensaje, DatagramSocket socket)  {

		String caller = mensaje.getFromUri().split(":")[1];
		String called = mensaje.getToUri().split(":")[1];
		boolean caller_regis = false;
		boolean called_regis = false;
		boolean both_regis = false;
		String IPCaller = null;
		String IPCalled = null;
		int portCaller = 0;
		int portCalled = 0;



		// Obtener las IP y puertos de llamante y llamado
		for (int i=0 ; i<usuariosRegistrados.size(); i++) {
			if (usuariosRegistrados.get(i).getNombreUsuario().equals(caller.split("@")[0]) ) {
				caller_regis = true;
				IPCaller = usuariosRegistrados.get(i).getIP();
				portCaller = Integer.parseInt(usuariosRegistrados.get(i).getPuerto());
			}
			if (usuariosRegistrados.get(i).getNombreUsuario().equals(called.split("@")[0]) ) {
				called_regis = true;
				IPCalled = usuariosRegistrados.get(i).getIP();
				portCalled = Integer.parseInt(usuariosRegistrados.get(i).getPuerto());
			}
		}

		// Si los dos estan registrados se gestiona el Invite; si no, se envia mensaje 404
		both_regis = caller_regis && called_regis;

		if (both_regis) {

			// Si aun no se ha autenticado se envia reto de autentificacion; si esta autenticado se gestiona
			boolean autenticado = compruebaAuthentication(mensaje);

			if ( (!autenticado) & (mensaje.getProxyAuthentication() == null) ) {
				enviaAuthentication(mensaje, IPCaller, portCaller); //Envio de mensaje 407
			}

			else if (autenticado){

				proxyOcupado = true;

				// Enviar INIVITE al llamado
				enviarMensajeInvite(mensaje, IPCalled, portCalled);

				// Enviar trying al llamante
				enviarMensajeTrying(mensaje, IPCaller, portCaller);
			}
			else {
				System.out.println("\nError de autentificacion.");
				return;
			}

		}

		else {

			NotFoundMessage mensaje404 = creaMensaje404(caller, called, Integer.toString(portCaller), mensaje.getCallId(),
					Integer.parseInt(mensaje.getcSeqNumber()));

			int portCallerContact = Integer.parseInt(mensaje.getContact().split(":")[1]);

			enviarMensajeSIP((SIPMessage)mensaje404, mensaje.getContact().split(":")[0], portCallerContact);
		}

		}


		public static boolean compruebaAuthentication(InviteMessage mensaje) {

			boolean autenticado = false;
			
			String usuario = mensaje.getFromUri();
		
			String pass = null;

			for (int i=0 ; i<usuariosPermitidos.size(); i++) {
				if (usuariosPermitidos.get(i).getNombreUsuario().equals(mensaje.getFromUri().split(":")[1].split("@")[0]) ) {
					pass = usuariosPermitidos.get(i).getPassword();				}
			}	
			


			if (mensaje.getProxyAuthentication() != null){

				String hash = codigo + pass;		
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


				if (md5Hexa.toString().equals(mensaje.getProxyAuthentication())) 
					autenticado = true;
				
				else {

					String called = mensaje.getToUri();
					ArrayList<String> viaUA = new ArrayList<String>();
					viaUA = mensaje.getVias();
					InetAddress ip = null;
					try {
						ip = InetAddress.getByName(viaUA.get(0).split(":")[0]);
					} catch (UnknownHostException e) {
						e.printStackTrace();
					}
					String port = viaUA.get(0).split(":")[1];

					NotFoundMessage mensaje404 = creaMensaje404(usuario, called, port, mensaje.getCallId(), 
							Integer.parseInt(mensaje.getcSeqNumber()));
							byte[] dataBytes = mensaje404.toStringMessage().getBytes();
					DatagramPacket paquete404 = new DatagramPacket(dataBytes, dataBytes.length, ip, Integer.parseInt(port));
					try {
						socket.send(paquete404);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}

			return autenticado;
		}


		static void enviaAuthentication(InviteMessage mensaje, String IPCaller,int portCaller) {

			// Crear mensaje autenticacion
			ProxyAuthenticationMessage mensajeAuthentication = new ProxyAuthenticationMessage();

			mensajeAuthentication.setFromUri(mensaje.getFromUri());
			mensajeAuthentication.setToUri(mensaje.getToUri());
			mensajeAuthentication.setVias(mensaje.getVias());
			mensajeAuthentication.setproxyAuthenticate(codigo);
			mensajeAuthentication.setcSeqNumber(mensaje.getcSeqNumber());
			mensajeAuthentication.setcSeqStr(mensaje.getcSeqStr());
			mensajeAuthentication.setCallId((mensaje.getCallId()));

			//Enviar mensaje
			enviarMensajeSIP(mensajeAuthentication, IPCaller, portCaller);

		}

		static String crearContraseñaProxy() {

			new Random().nextBytes(randombuf);

			// Convertir a Hexadecimal
			StringBuffer bufferString = new StringBuffer();
			for (int i = 0; i < randombuf.length; i++) {
				bufferString.append(Integer.toHexString(randombuf[i]));
			}

			return bufferString.toString();
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
				socket.send(paquete);
			} catch (IOException exception) {
				exception.printStackTrace();
			}
		}

		static void enviarMensajeInvite(InviteMessage mensajeInviteRecibido, String IPCalled, int portCalled) {

			InviteMessage mensajeInvite = new InviteMessage();

			mensajeInvite = mensajeInviteRecibido;
			mensajeInvite.addVia(IPCalled + ":" + puertoescuchaProxy);
			mensajeInvite.setMaxForwards(mensajeInviteRecibido.getMaxForwards()-1);
			if (loose_routing)
				mensajeInvite.setRecordRoute(IPCalled + ":" + puertoescuchaProxy);

			enviarMensajeSIP((SIPMessage)mensajeInvite, IPCalled, portCalled);
		}

		static void enviarMensajeTrying(InviteMessage mensaje, String IPCaller, int portCaller) {

			TryingMessage mensajeTrying = new TryingMessage();

			mensajeTrying.setVias(mensaje.getVias());
			mensajeTrying.setToUri(mensaje.getToUri());
			mensajeTrying.setFromUri(mensaje.getFromUri());
			mensajeTrying.setCallId(mensaje.getCallId());
			mensajeTrying.setcSeqStr(mensaje.getcSeqStr());
			mensajeTrying.setcSeqNumber(mensaje.getcSeqNumber());
			mensajeTrying.setContentLength(0);

			enviarMensajeSIP(mensajeTrying, IPCaller, portCaller);
		}

		public static void gestionaMensajeACK(ACKMessage mensaje) {

			String IPProxy = getIPProxy();
			String usuario = mensaje.getToUri();
			String IPUsuario = null;
			int portUsuario = 0;

			for (int i=0 ; i<usuariosRegistrados.size(); i++) {
				if (usuariosRegistrados.get(i).getNombreUsuario().equals(usuario) ) {
					IPUsuario = usuariosRegistrados.get(i).getIP();
					portUsuario = Integer.parseInt(usuariosRegistrados.get(i).getPuerto());
				}
			}	

			// Si hay cabecera Route, se borra
			if (mensaje.getRoute() != null)
				mensaje.setRoute(null);
			mensaje.addVia(IPProxy + ":" + puertoescuchaProxy);
			mensaje.setMaxForwards(mensaje.getMaxForwards()-1);

			enviarMensajeSIP(mensaje, IPUsuario, portUsuario); 

		}

		public static String getIPProxy() {

			String IPProxy = null;
			try {
				IPProxy = InetAddress.getLocalHost().getHostName();
			} catch (UnknownHostException exception) {
				exception.printStackTrace();
			}
			return IPProxy;
		}

		static void gestionaMensajeRinging(RingingMessage mensaje) {

			mensaje.deleteVia();

			ArrayList<String> viaUA = new ArrayList<String>();
			viaUA = mensaje.getVias();
			String IPCaller = viaUA.get(0).split(":")[0];
			String portCaller = viaUA.get(0).split(":")[1];

			enviarMensajeSIP((SIPMessage)mensaje, IPCaller, Integer.parseInt(portCaller));
		}


		public static void gestionaMensaje200OK(OKMessage mensaje) {

			mensaje.deleteVia();

			ArrayList<String> viaUA = new ArrayList<String>();
			viaUA = mensaje.getVias();
			String IPCaller = viaUA.get(0).split(":")[0];
			String portCaller = viaUA.get(0).split(":")[1];

			enviarMensajeSIP((SIPMessage)mensaje, IPCaller, Integer.parseInt(portCaller));

			// Si el 200 OK procede de un Bye, el Proxy ya no tiene que gestionar nada
			if (mensaje.getcSeqStr().equals("BYE"))
				proxyOcupado = false;

			// Si el 200 OK procede de un Invite y no hay Loose-Routin, el Proxy ya no tiene que gestionar nada
			if (mensaje.getcSeqStr().equals("INVITE") && mensaje.getRecordRoute() == null)
				proxyOcupado = false;
		}

		public static void gestionaMensajeBYE(ByeMessage mensaje){

			String IPProxy = getIPProxy();
			String usuario = mensaje.getToUri();
			String IPUsuario = null;
			int portUsuario = 0;

			for (int i=0 ; i<usuariosRegistrados.size(); i++) {
				if (usuariosRegistrados.get(i).getNombreUsuario().equals(usuario) ) {
					IPUsuario = usuariosRegistrados.get(i).getIP();
					portUsuario = Integer.parseInt(usuariosRegistrados.get(i).getPuerto());
				}
			}	

			// Si hay cabecera Route, se borra
			if (mensaje.getRoute() != null)
				mensaje.setRoute(null);
			mensaje.addVia(IPProxy + ":" + puertoescuchaProxy);
			mensaje.setMaxForwards(mensaje.getMaxForwards()-1);

			enviarMensajeSIP(mensaje, IPUsuario, portUsuario);  
		}

		public static void gestionaBusyHere(BusyHereMessage mensaje) {

			enviaACK(mensaje);
			proxyOcupado = false;

			mensaje.deleteVia();

			ArrayList<String> viaUA = new ArrayList<String>();
			viaUA = mensaje.getVias();
			String IPCaller = viaUA.get(0).split(":")[0];
			String portCaller = viaUA.get(0).split(":")[1];

			enviarMensajeSIP(mensaje, IPCaller, Integer.parseInt(portCaller));  			
		}

		public static void enviaACK(SIPMessage mensajeRecibido) {

			ACKMessage mensajeACK = new ACKMessage();
			String IPUsuario = null;
			int portUsuario = 0;

			if (mensajeRecibido instanceof BusyHereMessage) {
				BusyHereMessage mensaje = (BusyHereMessage) mensajeRecibido;
				
				mensajeACK.setFromUri(mensaje.getFromUri());
				mensajeACK.setToUri(mensaje.getToUri());
				mensajeACK.setCallId(mensaje.getCallId());
				mensajeACK.setcSeqStr("ACK");
				int seqNumber = Integer.parseInt(mensaje.getcSeqNumber())+1;
				mensajeACK.setcSeqNumber(""+seqNumber);
				mensajeACK.setMaxForwards(100);
				mensajeACK.setDestination(mensaje.getToUri());
				ArrayList<String> viaProxy = new ArrayList<String>();
				try {
					viaProxy.add(InetAddress.getLocalHost().getHostName() + ":" + puertoescuchaProxy);
				} catch (UnknownHostException e) {
					e.printStackTrace();
				}
				mensajeACK.setVias(viaProxy);

				String usuario = mensaje.getToUri();

				for (int i=0 ; i<usuariosRegistrados.size(); i++) {
					if (usuariosRegistrados.get(i).getNombreUsuario().equals(usuario) ) {
						IPUsuario = usuariosRegistrados.get(i).getIP();
						portUsuario = Integer.parseInt(usuariosRegistrados.get(i).getPuerto());
					}
				}
			}
			
			else if (mensajeRecibido instanceof RequestTimeoutMessage) {
				RequestTimeoutMessage mensaje = (RequestTimeoutMessage) mensajeRecibido;
				
				mensajeACK.setFromUri(mensaje.getFromUri());
				mensajeACK.setToUri(mensaje.getToUri());
				mensajeACK.setCallId(mensaje.getCallId());
				mensajeACK.setcSeqStr("ACK");
				int seqNumber = Integer.parseInt(mensaje.getcSeqNumber())+1;
				mensajeACK.setcSeqNumber(""+seqNumber);
				mensajeACK.setMaxForwards(100);
				mensajeACK.setDestination(mensaje.getToUri());
				ArrayList<String> viaProxy = new ArrayList<String>();
				try {
					viaProxy.add(InetAddress.getLocalHost().getHostName() + ":" + puertoescuchaProxy);
				} catch (UnknownHostException e) {
					e.printStackTrace();
				}
				mensajeACK.setVias(viaProxy);

				String usuario = mensaje.getToUri();

				for (int i=0 ; i<usuariosRegistrados.size(); i++) {
					System.out.println(usuariosRegistrados.get(i));
					if (usuariosRegistrados.get(i).getNombreUsuario().equals(usuario) ) {
						IPUsuario = usuariosRegistrados.get(i).getIP();
						portUsuario = Integer.parseInt(usuariosRegistrados.get(i).getPuerto());
						
					}
				}
			}
           
			enviarMensajeSIP(mensajeACK, IPUsuario, portUsuario); 
		}


		public static void gestionaMensajeTimeout(RequestTimeoutMessage mensaje) {

			proxyOcupado = false;

			// Enviar mensaje ACK al llamado
			enviaACK(mensaje);

			// Reenviar el Timeout al llamante (IP y puerto se obtienen del From)
			String usuario = mensaje.getFromUri();
			String IPUsuario = null;
			int puertoUsuario = 0;

			for (int i=0 ; i<usuariosRegistrados.size(); i++) {
				//System.out.println(usuariosRegistrados.get(i).getIP());
				if (usuariosRegistrados.get(i).getNombreUsuario().equals(usuario) ) {
					
					IPUsuario = usuariosRegistrados.get(i).getIP();
					puertoUsuario = Integer.parseInt(usuariosRegistrados.get(i).getPuerto());
				}
			}

			enviarMensajeSIP(mensaje, IPUsuario, puertoUsuario);
		}


		public static Timer iniciaTimer200ms(SIPMessage mensaje, int puertoescuchaUA, String IPUsuario){

			//Se define el timer de 200 ms
			Timer timer = new Timer(200, new ActionListener () { 
				public void actionPerformed(ActionEvent e) {
					if (contador < 4) {
						enviarMensajeSIP(mensaje, IPUsuario, puertoescuchaUA);
						contador++;
					}
					else {
						((Timer) e.getSource()).stop(); //Parar timer si ya se han enviado 5 mensajes
						contador = 0;
					}
				} 
			}); 

			// Se inicia el timer
			timer.setRepeats(true);
			timer.start();
			return timer;
		}


		public static void paraTimer(Timer timer) {
			if (timer != null) {
				timer.stop();
				System.out.println("El Timer de " + timer.getDelay() + "ms ha sido parado.");
			}
			else
				System.out.println("Timer ya parado.");
		}

	}
