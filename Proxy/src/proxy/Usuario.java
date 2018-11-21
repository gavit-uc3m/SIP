package proxy;

public class Usuario {
	
	public String nombreUsuario;
	public String password;
	public String puerto;
	public String IP;


  public Usuario(String Nombre, String Pass) {
		
	  nombreUsuario = Nombre;
	  password = Pass;
	  puerto = null;
	  IP = null;
	  
	}
  
  public Usuario(String Nombre ,String port, String ip) {
	  nombreUsuario = Nombre;
	  puerto = port;
	  IP = ip;
	  
	 }
  
    public String getNombreUsuario() {
		return nombreUsuario;
	}
	
	public String getPassword() {
		return password;
	}
	
	

	public String getPuerto() {
		return puerto;
	}

	public String getIP() {
		return IP;
	}



}
