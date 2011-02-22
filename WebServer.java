
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.net.*;

class WebServer 
{
    static String root = System.getProperty("user.home") + "\\public_html";
    
    final static int BUFFER_SIZE = 2048;
    
    //server response codes
    final static int OK = 200;
    final static int NOT_FOUND = 404;
    final static int METHOD_NOT_SUPPORTED = 405;
    final static int NOT_MODIFIED = 304;
    
    //server timeout information
    final static int STAY_ALIVE = 5;
    final static int MAX_CONNECTIONS = 100;
    
    //end of a line in packet
    final static String LINE_END = "\r\n";
    static ServerSocket serverSocket;
    static Socket connection;
    static byte[] recvData = new byte[BUFFER_SIZE];
    static byte[] sendData = new byte[BUFFER_SIZE];
    
    public static void main(String args[]) throws Exception
    {
        serverSocket = new ServerSocket(8080);
        
        int ids = 0;
        while(true)
        {                       
            connection = serverSocket.accept();
            //System.out.println("connection made");
            ClientHandler ch = new ClientHandler(connection, ids);
            (new Thread(ch, "Client Handler - " + ids)).start();
            
            ids++;            
        }
    }
}

//this is the class that does all the work
class ClientHandler extends WebServer implements Runnable
{
    private Socket clientConnection;
    private int connectionCount = 0;
    private int id = 0;
    private InputStream is = null;
    private PrintStream ps = null;
    
    public ClientHandler(Socket connection, int clientID) throws Exception
    {
        clientConnection = connection;
        id = clientID;
        clientConnection.setSoTimeout(STAY_ALIVE * 1000); //convert from seconds to milli
        is = new BufferedInputStream(clientConnection.getInputStream());
        ps = new PrintStream(clientConnection.getOutputStream());
    }
    
    //continue to run with this handler until max connections or timeout
    public void run()
    {
        while(connectionCount < MAX_CONNECTIONS)
        {
            //handle the incoming packet
            try
            {
                parsePacket();
            } 
            catch (Exception e)
            {
                //for some reason this gets massive amounts of exceptions
                //it appears that the server is attempting too many connections
                //too quickly. It as if parsePacket(); is not blocking any
                //execution causing exceptions to be thrown when creating
                //the InputStream.
                
                //I think...
                
                //commenting out the stack trace to avoid exception spam.
                //e.printStackTrace();
            }
            
            //increment connectionCount
            connectionCount++;
        }
        if(connectionCount == MAX_CONNECTIONS)
        {
            System.out.println("client handler " + id + " max connections reached");
        }
        try 
        {
            closeConnections();
        } 
        catch (Exception e) 
        {
            e.printStackTrace();
        }
    }
    
    private void parsePacket() throws Exception
    {
        int code = 0;
        File file = null;
        
        int keepAliveTime = 0;
        boolean keepAlive = false;
        boolean isGet = false;
        String ifModifiedDate = "";
        
        //clearing out recvData
        for(int i = 0; i < recvData.length; i++)
        {
            recvData[i] = 0;
        }
        
        //System.out.println("about to read");
        
        //read into recvData until cap is hit
        int read = 0;
        try 
        {
            read = is.read(recvData, read, recvData.length);
        } 
        catch (SocketTimeoutException e) {
            //the socket has timed out and needs to be closed
            System.out.println("Timeout for ClientHandler " + id);
            //this forces the run while loop to exit
            connectionCount = MAX_CONNECTIONS + 10;
            return;
        }
        
        //System.out.println("read");
        
        //convert recvData to string to see what is in it
        //String data = new String(recvData);
        //System.out.println(data);
        
        String toString = new String(recvData);
        String[] lines = toString.split(LINE_END);
        
        //printing lines to see what we got
        for(int i = 0; i < lines.length; i++)
        {
            //System.out.println(lines[i]);
            if(lines[i].startsWith("GET"))
            {
                isGet = true;
            }
            else if(lines[i].startsWith("Keep-Alive"))
            {
                //need to grab the int from this line
                
                //keepAliveTime appears to be a deprecated in http1.1
                //im still grabbing it here but i will not be using it
                //when i create the headers.
                String[] subStrings = lines[i].split(" ");
                keepAliveTime = Integer.parseInt(subStrings[1]);
            }
            else if(lines[i].startsWith("Connection"))
            {
                if(lines[i].contains("keep-alive"))
                {
                    keepAlive = true;
                    //System.out.println("Staying Alive");
                }
            }
            else if(lines[i].startsWith("If-Modified-Since"))
            {
                String[] dates = lines[i].split(" ");
                ifModifiedDate = dates[1];
                System.out.println(ifModifiedDate);
            }
        }
            
        if(isGet)
        {
            System.out.println("Method: GET");
            
            //grabbing the file path
            String[] firstLines = lines[0].split(" ");
            String fileName = firstLines[1];
            
            //altering slashes for windows (/ -> \)
            fileName = fileName.replace('/', File.separatorChar);                   
            System.out.print("Send file path: ");
            System.out.println(root + fileName);

            //checking if the file grabbed from the incoming packet exists
            file = new File(root, fileName);
            if(file.exists())
            {
                code = OK;
            }
            else
            {
                code = NOT_FOUND;
            }
            
            //I am unsure how to test this. The code appears to be correct, however
            //I could not get wireshark to work for localhost. I think what may
            //be happening is firefox is not sending an if-modified-since header
            //for me to parse
            
            //check when modified last
            if(!ifModifiedDate.equals(""))
            {
                //date format of the packet's date
                SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
                
                //converting string to a date
                Date ifModified = sdf.parse(ifModifiedDate);
                
                //grabbing the file's last modified time and turning into date
                Long longDate = file.lastModified();
                Date fileModified = new Date(longDate);

                //if the file has been modified since the last time the browser
                //has seen it the send not_modified
                if(ifModified.compareTo(fileModified) < 0)
                {
                    code = NOT_MODIFIED;
                }
            }
        }
        else
        {
            System.out.println("405 Method Not Allowed");
            code = METHOD_NOT_SUPPORTED;
        }
        
        //proceed to create the header for packet
        generateHeaders(file, code, keepAlive);
    }
    
    private void generateHeaders(File file, int code, boolean keepAlive) throws Exception
    {
        
        String filePath = file.getAbsolutePath();
        //checking if the file exists
        switch (code)
        {
        case OK:
            ps.print("HTTP/1.1 " + OK + " OK");
            ps.print(LINE_END);
            break;
        
        case NOT_FOUND:
            ps.print("HTTP/1.1 " + NOT_FOUND + " Not Found");
            ps.print(LINE_END);
            sendNotFound();
            return;
            
        case NOT_MODIFIED:
            ps.print("HTTP/1.1 " + NOT_MODIFIED + " Not Modified");
            ps.print(LINE_END);
            sendNotModified();
            return;
        case METHOD_NOT_SUPPORTED:
            ps.print("HTTP/1.1 " + METHOD_NOT_SUPPORTED + " Method Not Supported");
            ps.print(LINE_END);
            sendNotSupported();
            return;
        }
        
        //send connection keep alive
        if(keepAlive)
        {
            ps.print("Keep-Alive: timeout = " + STAY_ALIVE + ", max = " + MAX_CONNECTIONS);
            ps.print(LINE_END);
            ps.print("Connection: Keep-Alive");
        }
        else
        {
            ps.print("Connection: Close");
        }
        ps.print(LINE_END);
        
        //check if file or directory
        //then send content type
        if(!file.isDirectory())
        {
            //send content length
            ps.print("Content-Length: " + file.length());
            ps.print(LINE_END);
            
            //grab the file type from the end of the string
            int typeStart = filePath.lastIndexOf('.');
            String type = filePath.substring(typeStart);
            if(type.equals(".htm") || type.equals(".html"))
            {
                ps.print("Content-Type: text/html");
                ps.print(LINE_END);
            }
            else if(type.equals(".jpg") || type.equals(".jpeg"))
            {
                ps.print("Content-Type: image/jpeg");
                ps.print(LINE_END);
            }
            else
            {
                ps.print("Content-Type: content/unknown");
                ps.print(LINE_END);
            }
        }
        else //here we would grab index.html --> text/html
        {
            //change the file path to point to the index of this directory
            file = new File(file, File.separatorChar + "index.html");
            
            ps.print("Content-Type: text/html");
            ps.print(LINE_END);
        }
        //final EOL representing data is next
        ps.print(LINE_END);
        
        //send data
        sendFile(file);
    }
    
    private void sendFile(File file) throws Exception
    {
        //stream to read the file
        InputStream is = new FileInputStream(file);
        int check;
        
        //send the data
        while((check = is.read(sendData)) != -1)
        {
            ps.write(sendData, 0, check);
        }
        
        //close the streams
        is.close();
        ps.close();
    }
    
    private void sendNotFound()
    {
        ps.print(LINE_END);
        ps.print("404 Not Found");
    }
    
    private void sendNotSupported()
    {
        ps.print(LINE_END);
        ps.print("405 Method Not Supported");
    }
    
    private void sendNotModified()
    {
        ps.print(LINE_END);
        ps.print("304 File Not Modified");
    }
    
    private void closeConnections() throws Exception
    {
        ps.close();
        is.close();
        connection.close();
    }
}