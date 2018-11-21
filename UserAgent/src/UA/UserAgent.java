package UA;

import mensajesSIP.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import javax.swing.Timer;
import java.awt.event.*;


public class UserAgent {

	private static String usuarioSIP;
	private static int puertoescuchaUA;
	private static String IPProxy;
	private static int puertoescuchaProxy;
	private static String debug1;
	private static boolean debug = true;
	private static UAListener uaListener;
	private static int contador;
	private static String estado = "";
    private static String dominio = "UC3M.es";
	private static boolean ocupado = false;
	private static boolean recibeLlamada = false;
	private static boolean terminaLlamada = false;
	private static InviteMessage copiaInvite;
	private static OKMessage copiaOK;
	private static ProxyAuthenticationMessage copiaAutenticacion;
	private static Timer timer200ms;
	private static Timer timer1s;
	private static Thread hiloEscucha = null;
	private static DatagramSocket socket;



    public static void main(String args[]) {

    	boolean registroCorrecto = false;

    	System.out.println("\nUser Agent iniciado correctamnete\n");

    	try {

    		// CAPTURA DE ARGUMENTOS
    		if (capturaArgs(args) == -1)
    			return;
    		System.out.println("IP Proxy:" + IPProxy);
		System.out.println("PuertoUA:" + puertoescuchaUA);
    		System.out.println("PuertoProxy:" + puertoescuchaProxy);

    		if ( debug1.equals("true") ) 
    			debug = true;
    		else if  (debug1.equals("false"))
    			debug = false;
    		else
    			System.out.println("Error. Parametro 'debug' mal escrito.");


    		// Creacion de socket

    		try {
    			socket = new DatagramSocket(puertoescuchaUA,InetAddress.getByName(IPProxy));
    		} catch (UnknownHostException e) {
    			e.printStackTrace();
    		}

    		// REGISTRO DEL USUARIO 
    	    registroCorrecto = registraUsuario(usuarioSIP, IPProxy, puertoescuchaProxy, puertoescuchaUA, 
    	    		socket);
    		
    		// HILO DE ESCUCHA
    		uaListener = new UAListener(socket, socket, IPProxy, puertoescuchaProxy);
    		hiloEscucha = new Thread(uaListener, "hilo_escucha");
    		hiloEscucha.start();
    		
    		Scanner escaner = new Scanner (System.in);
    	
    		while (registroCorrecto) {
    			
        		System.out.println("\nEscriba la accion que desea realizar:");
        		String userInput = null;
    			
    			//Comprobar que se ha escrito algo
    			if (escaner.hasNextLine())
        			userInput = escaner.nextLine ();
    			else
    				continue;
    			
    			//Gestionar input
    			
    			if (userInput.startsWith("INVITE")) {
    				
    				gestionaInvite(userInput, usuarioSIP, Integer.toString(puertoescuchaUA), IPProxy, Integer.toString(puertoescuchaProxy), socket);

    			}
    			
    			else if (userInput.equals("BYE")) {
    				if (estado.equals("Terminated")) {
    					System.out.println("Terminando la llamada... ");
    					enviaBYE();
    				}
    				else
    					System.out.println("Error en BYE. No hay llamada en curso.");
    			}
    			
    			else if ( (userInput.equals("si") || userInput.equals("no"))  && recibeLlamada) {
    				
    				respondeLlamada(userInput, copiaInvite, usuarioSIP, puertoescuchaUA, socket);

    			}
    			
    			else if (userInput.equals("salir")) {
    				System.out.println("Saliendo del programa...");
    				System.exit(0);
    			}
    			
    			else if (UAListener.getEsperandoPass()) {
    				UAListener.autenticaUsuario(copiaAutenticacion, IPProxy, puertoescuchaProxy, userInput);
    				UAListener.setEsperandoPass(false);
    				System.out.println("Contraseña introducida");
    			}
    			
    			else {
    				System.out.println("Error. El texto introducido es incorrecto, vuelva a intentarlo.");
    				continue;
    			}
    		}
    		
    		
    		System.out.println("Fin del programa");
    		if(escaner!=null)
    	        escaner.close();


    	} catch (SocketException e) {
    		System.out.println("Socket: " + e.getMessage());
    	} 
    }


    

	public static int capturaArgs(String[] args) {
		
		switch(args.length) {
		case 0:
			puertoescuchaUA = 5070;
			IPProxy = "192.168.1.1";
			puertoescuchaProxy = 5060;
			debug1 = "false";
			return 0;

		case 5:
			puertoescuchaUA = Integer.parseInt(args[1]);
			IPProxy = args[2];
			puertoescuchaProxy = Integer.parseInt(args[3]);
			debug1 = args[4];

			usuarioSIP = args[0];
			String usuario[] = usuarioSIP.split("@");
			if (usuario.length == 1)
				usuarioSIP = usuarioSIP + "@domain";
			else if (usuario.length == 2) {
				if (!(usuario[1].equals("domain"))) {
					System.out.println("Dominio erroneo.");
					return -1;
				}
			}
			else{
				System.out.println("Usuario erroneo.");
				return -1;
			}
			return 0;
			
		default:
			System.out.println("Error. Argumentos insuficientes");
			return -1;
		}
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
    
    public static RegisterMessage creaRegisterMessage(String usuarioSIP, String puertoescuchaUA,
                                                        String IPProxy, String puertoescuchaProxy) {

        // Registro del usuario SIP en el proxy
        RegisterMessage Register = new RegisterMessage();
        int cSeq = 1;

        ArrayList<String> vias = new ArrayList<String>();
        try {
            vias.add(InetAddress.getLocalHost().getHostAddress() + ":" + puertoescuchaProxy);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        Register.setDestination("sip:" + usuarioSIP);
        Register.setMaxForwards(70);
        Register.setExpires("100");
        Register.setContentLength(151);
        Register.setVias(vias);
        Register.setToUri("sip:" + usuarioSIP);
        Register.setFromUri("sip:" + usuarioSIP);
        Register.setCallId((new Random().nextLong() & 0xfffffff) + "@" + dominio);
        Register.setcSeqNumber(Integer.toString(cSeq));
        Register.setcSeqStr("REGISTER");
		try {
			Register.setContact(InetAddress.getLocalHost().getHostAddress() + ":" + puertoescuchaUA);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}

        return Register;
    }
    

    public static boolean registraUsuario(String usuarioSIP, String IPProxy, int puertoescuchaProxy,
    		int puertoEscuchaUsuario, DatagramSocket socket) {
	    	
    		boolean registroCompletado = false;
    		
    		// ENVIO DEL REGISTRO DE USUARIO
	    	RegisterMessage mensaje_registro  = creaRegisterMessage(usuarioSIP, Integer.toString(puertoescuchaUA), IPProxy,
	    			Integer.toString(puertoescuchaProxy));
	    	
	    	InetAddress direccionInet = null;
	    	try {
	    		direccionInet = InetAddress.getByName(IPProxy);
	    	} catch (UnknownHostException exceptionInet) {
	    		exceptionInet.printStackTrace();
	    	}

	    	byte[] dataBytes = mensaje_registro.toStringMessage().getBytes();
	    	DatagramPacket paquete_send = new DatagramPacket(dataBytes, dataBytes.length, direccionInet, puertoescuchaProxy);

	    	try {
	    		socket.send(paquete_send);
	    	} catch (IOException exception) {
	    		exception.printStackTrace();
	    	}

	    	System.out.println("\nRegisterMessage enviado\n");

	
	    	// RECEPCION DE MENSAJE OK
	    	byte[] buffer = new byte[2000];
	    	DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
	    	boolean OKrecibido = false ;
	
	    	while (!OKrecibido) {
	    		try {
	    			socket.setSoTimeout(2000);
	    			socket.receive(paquete);
	    			OKrecibido = true;
	    			System.out.println("\n200OK del Register recibido\n");
	
	    			String paqueteString = new String(paquete.getData(), StandardCharsets.UTF_8);
	
	    			if (debug)
	    				System.out.println(paqueteString);
	    			else
	    				System.out.println(paqueteString.split("\n")[0]);
	    			
	    			SIPMessage mensajeSIP = null;
	    			try {
	    				mensajeSIP = SIPMessage.parseMessage(paqueteString);
	    			} catch (SIPException e) {
	    				e.printStackTrace();
	    			}

	    			if (mensajeSIP.getClass() == NotFoundMessage.class)
	    				registroCompletado = false;
	    			else
	    				registroCompletado = true;
	
	    		} catch (SocketException exception) {
	    			enviarMensajeSIP( mensaje_registro, IPProxy, puertoescuchaProxy);
	    			exception.printStackTrace();
	    		} catch (IOException ioexception) {
	    			ioexception.printStackTrace();
	    		}
	    	}

	    	try {
	    		socket.setSoTimeout(0);
	    	} catch (SocketException e) {
	    		e.printStackTrace();
	    	}
	    	
	    	System.out.println("Registro completado");
	    	return registroCompletado;
    }

    public static void gestionaInvite(String userInput, String usuarioSIP, String puertoescuchaUA,
            String IPProxy, String puertoescuchaProxy, DatagramSocket socket) {
    
		String llamado = userInput.split(" ")[1];
		boolean llamadaValida = false;
		String[] llamadoUsuario = llamado.split("@");
		
		if (llamadoUsuario.length == 1) {
			llamado = llamado + "@domain";
			llamadaValida = true;
		}
		else if (llamadoUsuario.length == 2) {
			if (!(llamadoUsuario[1].equals("domain"))) 
				System.out.println("Dominio no valido.");
			else
				llamadaValida = true;
		}
		else
			System.out.println("Usuario no valido.");
		
		if (llamadaValida) {
			setEstado("Calling");
			enviaInvite(usuarioSIP, llamado, IPProxy, socket, Integer.parseInt(puertoescuchaUA),
					Integer.parseInt(puertoescuchaProxy));
			ocupado = true;
		}
    }
    
	static void enviaInvite(String usuarioSIP, String llamado, String IPProxy, DatagramSocket socketUA,
			int puertoescuchaUsuario, int puertoescuchaProxy) {
		
		InviteMessage mensaje = new InviteMessage();

		ArrayList<String> viaUA = new ArrayList<String>();
		try {
			viaUA.add(InetAddress.getLocalHost().getHostAddress() + ":" + puertoescuchaUsuario);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}

		mensaje.setVias(viaUA);
		mensaje.setToUri("sip:" + llamado);
		mensaje.setFromUri("sip:" + usuarioSIP);
		mensaje.setcSeqStr("INVITE");
		mensaje.setcSeqNumber("1000");
		Random randomno = new Random();
		mensaje.setCallId("" + Integer.toString(randomno.nextInt(5000)));
		mensaje.setDestination("sip:" + llamado);
		mensaje.setMaxForwards(70);
		try {
			mensaje.setContact(InetAddress.getLocalHost().getHostAddress() + ":" + puertoescuchaUsuario);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		
		setCopiaInvite(mensaje);

		// SDP
		SDPMessage sdp = new SDPMessage();
		
		ArrayList<Integer> opciones = new ArrayList<Integer>();
		opciones.add(96);
		opciones.add(97);
		opciones.add(98);

		try {
			sdp.setIp(InetAddress.getLocalHost().getHostAddress() );
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		sdp.setPort(puertoescuchaUsuario);
		sdp.setOptions(opciones);

		mensaje.setSdp(sdp);
		mensaje.setContentLength(sdp.toStringMessage().length());
		mensaje.setContentType("application/sdp");
		
		//Enviar mensaje
		enviarMensajeSIP( mensaje, IPProxy, puertoescuchaProxy);
	}


	public static void respondeLlamada(String userInput, InviteMessage mensajeInvite, String  usuarioSIP, int puertoescuchaUA, DatagramSocket socketUA) {
		
		if (userInput.equals("si")) {
			
			OKMessage mensaje = new OKMessage();

			try {
				mensaje.setContact(InetAddress.getLocalHost().getHostAddress() + ":" + puertoescuchaUA);
			} catch (UnknownHostException e1) {
				e1.printStackTrace();
			}
			mensaje.setExpires("100");
			mensaje.setVias(mensajeInvite.getVias());
			mensaje.setToUri(mensajeInvite.getToUri());
			mensaje.setFromUri(mensajeInvite.getFromUri());
			mensaje.setCallId(mensajeInvite.getCallId());
			mensaje.setcSeqStr(mensajeInvite.getcSeqStr());
			mensaje.setcSeqNumber(mensajeInvite.getcSeqNumber());

			if (UAListener.getLooseRouting())
				mensaje.setRecordRoute(mensajeInvite.getRecordRoute());

			// SDP
			SDPMessage sdp = new SDPMessage();

			ArrayList<Integer> opciones = new ArrayList<Integer>();
			opciones.add(96);
			opciones.add(97);
			opciones.add(98);

			try {
				sdp.setIp(InetAddress.getLocalHost().getHostAddress() );
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
			sdp.setPort(puertoescuchaUA);
			sdp.setOptions(opciones);

			mensaje.setSdp(sdp);
			mensaje.setContentLength(sdp.toStringMessage().length());
			
			enviarMensajeSIP((SIPMessage)mensaje, IPProxy, puertoescuchaProxy);
			
			setEstado("Terminated");
			ocupado = true;
			setCopiaOK(mensaje);

		}
		
		else if (userInput.equals("no")) {
			
			BusyHereMessage mensaje = new BusyHereMessage();
			
			mensaje.setContentLength(0);
			mensaje.setVias(mensajeInvite.getVias());
			mensaje.setToUri(mensajeInvite.getToUri());
			mensaje.setFromUri(mensajeInvite.getFromUri());
			mensaje.setCallId(mensajeInvite.getCallId());
			mensaje.setcSeqStr(mensajeInvite.getcSeqStr());
			mensaje.setcSeqNumber(mensajeInvite.getcSeqNumber());
			
			enviarMensajeSIP((SIPMessage)mensaje, IPProxy, puertoescuchaProxy);
			
			timer200ms = iniciaTimer200ms(mensaje, puertoescuchaProxy, IPProxy);
			
			setEstado("Completed");
			timer1s = iniciaTimer1s();
		}
		
		else
			return;
		
		setRecibeLlamada(false);
		paraTimer(UAListener.getTimer10s());
		
	}
	
	public static void enviaBYE() {
		
		setTerminaLlamada(true);
		ByeMessage mensaje = new ByeMessage();
		String[] destino = null;
		String IP = null;
		int puerto = 0;

		mensaje.setFromUri("sip:" + usuarioSIP);
		if ( copiaInvite.getFromUri().equals(mensaje.getFromUri()) ) {
			mensaje.setToUri(copiaInvite.getToUri());
			destino = copiaOK.getContact().split(":");
		}
		else {
			mensaje.setToUri(copiaInvite.getFromUri());
			destino = copiaInvite.getContact().split(":");
		}

		if (UAListener.getLooseRouting()) {
			mensaje.setRoute(copiaOK.getRoute());
			IP = IPProxy;
			puerto = puertoescuchaProxy;
		}
		else {
			IP = destino[1];
			puerto = Integer.parseInt(destino[2]);
		}
		
		ArrayList<String> viaUA = new ArrayList<String>();
		try {
			viaUA.add(InetAddress.getLocalHost().getHostAddress() + ":" + puertoescuchaUA);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}

		mensaje.setVias(viaUA);
		mensaje.setDestination(mensaje.getToUri());
		mensaje.setCallId(copiaInvite.getCallId());
		mensaje.setcSeqStr("BYE");
		mensaje.setMaxForwards(100);
		mensaje.setcSeqNumber("1");

		enviarMensajeSIP(mensaje, IP, puerto);

	}
	

	
	public static Timer iniciaTimer1s() {
		Timer timer = new Timer(1000, new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if(getEstado().equals("Completed"))
					setEstado("Terminated");
			}
		});
		timer.setRepeats(false);
		timer.start();
		System.out.println("Timer 1s iniciado.");
		return timer;
	}
	
	public static Timer iniciaTimer200ms(SIPMessage mensaje, int puertoescuchaUA, String IPUsuario){
		
		contador = 0;
		
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
			System.out.println("Timer parado al cabo de " + timer.getDelay() + "ms.");
		}
	}
	
	
	//------------ Getters y setters ------------
	
	public static void setEstado(String nuevoEstado) {
		System.out.println("El estado actual es: " + nuevoEstado + "\n");
		estado = nuevoEstado;
	}
	
	public static String getEstado() {
		return estado;
	}
	
	public static boolean getOcupado() {
		return ocupado;
	}
	
	public static void setOcupado(boolean bool) {
		ocupado = bool;
	}
	
	public static void setRecibeLlamada(boolean valor) {
		recibeLlamada = valor;
	}

	public static void setTerminaLlamada(boolean bool) {
		terminaLlamada = bool;
	}
	
	public static boolean getTerminaLlamada() {
		return terminaLlamada;
	}
	
	public static void setCopiaInvite(InviteMessage mensaje){
		copiaInvite = mensaje;
	}
	
	public static InviteMessage getCopiaInvite() {
		return copiaInvite;
	}
	
	public static void setCopiaOK(OKMessage mensaje){
		copiaOK = mensaje;
	}

	public static OKMessage getCopiaOK() {
		return copiaOK;
	}
	
	public static void setCopiaAutenticacion(ProxyAuthenticationMessage mensaje){
		copiaAutenticacion = mensaje;
	}
		
	public static int getPuertoEscuchaUA(){
		return puertoescuchaUA;
	}
	
	public static boolean getDebug() {
		return debug;
	}
	
	public static void setTimer200ms(Timer timer) {
		timer200ms = timer;
	}
	
	public static Timer getTimer200ms() {
		return timer200ms;
	}

	public static void setTimer1s(Timer timer) {
		timer1s = timer;
	}
	
	public static Timer getTimer1s() {
		return timer1s;
	}
	
}
