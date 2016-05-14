package naming;

import java.io.*;
import java.net.*;
import java.util.*;

import rmi.*;
import common.*;
import storage.*;

/** Naming server.

    <p>
    Each instance of the filesystem is centered on a single naming server. The
    naming server maintains the filesystem directory tree. It does not store any
    file data - this is done by separate storage servers. The primary purpose of
    the naming server is to map each file name (path) to the storage server
    which hosts the file's contents.

    <p>
    The naming server provides two interfaces, <code>Service</code> and
    <code>Registration</code>, which are accessible through RMI. Storage servers
    use the <code>Registration</code> interface to inform the naming server of
    their existence. Clients use the <code>Service</code> interface to perform
    most filesystem operations. The documentation accompanying these interfaces
    provides details on the methods supported.

    <p>
    Stubs for accessing the naming server must typically be created by directly
    specifying the remote network address. To make this possible, the client and
    registration interfaces are available at well-known ports defined in
    <code>NamingStubs</code>.
 */
public class NamingServer implements Service, Registration
{
    private Skeleton<Service> serviceSkeleton;
    private Skeleton<Registration> registrationSkeleton;
    private HashMap<Path, Storage> storageTable;
    private HashMap<Path, Command> commandTable;
    private Set<Storage> storages;  // Stores connected Storage stubs.
    private Set<Command> commands;  // Stores connected Command stubs.
    private Set<Path> createdDirs;  // Stores all created directories to distinguish them from files.

    private List<Pair> queue;
    /** Creates the naming server object.

        <p>
        The naming server is not started.
     */
    public NamingServer()
    {
        this.storageTable = new HashMap<>();
        this.commandTable = new HashMap<>();
        this.storages = new HashSet<Storage>();
        this.commands = new HashSet<Command>();
        this.createdDirs = new HashSet<Path>();

        this.queue = new ArrayList<>();
//        throw new UnsupportedOperationException("not implemented");
    }

    /** Starts the naming server.

        <p>
        After this method is called, it is possible to access the client and
        registration interfaces of the naming server remotely.

        @throws RMIException If either of the two skeletons, for the client or
                             registration server interfaces, could not be
                             started. The user should not attempt to start the
                             server again if an exception occurs.
     */
    public synchronized void start() throws RMIException
    {
        InetSocketAddress serviceAddress = new InetSocketAddress("127.0.0.1", NamingStubs.SERVICE_PORT);
        this.serviceSkeleton = new Skeleton<>(Service.class, this, serviceAddress);
        InetSocketAddress registrationAddress = new InetSocketAddress("127.0.0.1", NamingStubs.REGISTRATION_PORT);
        this.registrationSkeleton = new Skeleton<>(Registration.class, this, registrationAddress);

        this.serviceSkeleton.start();
        this.registrationSkeleton.start();
    }

    /** Stops the naming server.

        <p>
        This method commands both the client and registration interface
        skeletons to stop. It attempts to interrupt as many of the threads that
        are executing naming server code as possible. After this method is
        called, the naming server is no longer accessible remotely. The naming
        server should not be restarted.
     */
    public void stop()
    {
//        throw new UnsupportedOperationException("not implemented");
        try{
            this.serviceSkeleton.stop();
            this.registrationSkeleton.stop();
            this.stopped(null);
        }catch (Exception e){
            this.stopped(e);
        }
    }

    /** Indicates that the server has completely shut down.

        <p>
        This method should be overridden for error reporting and application
        exit purposes. The default implementation does nothing.

        @param cause The cause for the shutdown, or <code>null</code> if the
                     shutdown was by explicit user request.
     */
    protected void stopped(Throwable cause)
    {
    }

    // The following public methods are documented in Service.java.
    @Override
    public void lock(Path path, boolean exclusive) throws FileNotFoundException
    {

        synchronized (this){
            this.queue.add(new Pair(path, exclusive));
        }
        if(!exclusive){ //  read
            while(true){
                int i = 0;
                synchronized (this){
                    while(i<this.queue.size()){
                        Pair pair = this.queue.get(i);

                        if(pair.path == null)   System.out.println("NULL!!!!!!!!!!" + queue.size());
                        if(pair.path.equals(path) && pair.exclusive == exclusive)   break;
                        if(pair.exclusive){
                            boolean violate = checkViolateWithRead(pair.path, path);
                            if(violate) try {
                                wait();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        i++;
                    }
                    return;
                }
            }
        }else{
            while(true){
                int i = 0;
                synchronized (this){
                    while(i < this.queue.size()){
                        Pair pair = this.queue.get(i);
                        if(pair.path.equals(path) && pair.exclusive == exclusive)    break;

                        boolean violate = checkViolateWithWrite(pair.path, pair.exclusive, path);
                        if(violate) try {
                            wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        i++;
                    }
                    return;
                }
            }
        }
//        throw new UnsupportedOperationException("not implemented");
    }

    // writePath comes before readPath
    private boolean checkViolateWithRead(Path writePath, Path readPath){
        if(writePath.equals(readPath))  return true;
        if(writePath.isSubpath(readPath))   return false;    // write /a/b/c, read /a/b
        if(readPath.isSubpath(writePath))   return true;   // write /a/b, read /a/b/c
        return false;
    }

    // path comes before writePath
    private boolean checkViolateWithWrite(Path path, boolean exclusive, Path writePath){
        if(path.equals(writePath))  return true;
        if(!exclusive){
            if(writePath.isSubpath(path))   return false;    // read /a/b, write /a/b/c
            if(path.isSubpath(writePath))   return true;    // read /a/b/c, write /a/b
            return false;
        }else{
            if(writePath.isSubpath(path))   return true;    // write /a/b, write /a/b/c
            if(path.isSubpath(writePath))   return true;    // write /a/b/c, write /a/b
            return false;
        }
    }

    @Override
    public void unlock(Path path, boolean exclusive)
    {
        synchronized (this){
            this.queue.remove(new Pair(path, exclusive));
            notifyAll();
        }
//        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public boolean isDirectory(Path path) throws FileNotFoundException, RMIException {
        if(path == null)    throw new NullPointerException("Input null");
        if(path.isRoot())   return true;
        lock(path, false);
        if(!contains(path)){
            unlock(path, false);
            throw new FileNotFoundException("File not found");
        }
        if(this.createdDirs.contains(path)){
            unlock(path, false);
            return true;
        }
        for(Path p : this.storageTable.keySet()){
            if(p.equals(path)){
                unlock(path, false);
                return false;
            }
            if(p.isSubpath(path) && !p.equals(path)){
                unlock(path, false);
                return true;
            }
        }
        return false;
    }

    // This private method is only caller when the caller already has the lock.
    private boolean isDirectoryNoLock(Path path) throws FileNotFoundException {
        if(path == null)    throw new NullPointerException("Input null");
        if(path.isRoot())   return true;
        if(!contains(path)) throw new FileNotFoundException("File not found");
        if(this.createdDirs.contains(path)) return true;
        for(Path p : this.storageTable.keySet()){
            if(p.equals(path))  return false;
            if(p.isSubpath(path) && !p.equals(path))   return true;
        }
        return false;
    }

    @Override
    public String[] list(Path directory) throws FileNotFoundException, RMIException {
        // This method only lists direct children
        if(directory == null)    throw new NullPointerException("Input null");
        if(!this.isDirectoryNoLock(directory))    throw new FileNotFoundException("Not a directory");

        HashSet<String> ret = new HashSet<>();
        for(Path p : this.storageTable.keySet()){
            if(p.isSubpath(directory) && !p.equals(directory)){
                ret.add(p.getDirectChild(directory));
            }
        }
        return ret.toArray(new String[0]);
    }


    @Override
    public boolean createFile(Path file)
        throws RMIException, FileNotFoundException
    {
        if(file == null)    throw new NullPointerException("Input null");

        if(this.contains(file)) return false;   // Existing file name
        Path parent = file.parent();
        if(!this.isDirectoryNoLock(parent))   throw new FileNotFoundException("Parent is a file");
        Storage storage = this.getDirStorage(parent);
        if(storage == null) throw new FileNotFoundException("Parent not exist");
        if(this.storages.size() == 0)   throw new IllegalStateException("No connected storage servers");
        Command command = this.getDirCommand(parent);
        boolean b = command.create(file);
        if(!b)  return b;
        this.storageTable.put(file, storage);
        this.commandTable.put(file, command);
        return b;
    }

    @Override
    public boolean createDirectory(Path directory) throws FileNotFoundException, RMIException {
        if(directory == null)    throw new NullPointerException("Input null");

        if(directory.isRoot())  return false;
        Path parent = directory.parent();
        if(!this.isDirectoryNoLock(parent))   throw new FileNotFoundException("Parent is a file");
        if(this.contains(directory))    return false;   // Existing name
        Storage storage = this.getDirStorage(parent);
        Command command = this.getDirCommand(parent);

        // Only create the directory in the directory tree, but does not create actual folder in storage server
        this.storageTable.put(directory, storage);
        this.commandTable.put(directory, command);
        this.createdDirs.add(directory);
        return true;
    }

    // This method is not tested in the checkpoint!
    @Override
    public boolean delete(Path path) throws FileNotFoundException, RMIException {
        if(path == null)    throw new NullPointerException("Input null");

        if(path.isRoot())   return false;
        if(!this.contains(path))    throw new FileNotFoundException("Path not found");
        Command command = this.getDirCommand(path);

        boolean b = command.delete(path);
        if(!b)  return b;
        if(this.isDirectoryNoLock(path)){ // If path is directory, all children are also deleted from the tree.
            Iterator<Map.Entry<Path, Command>> iterator = this.commandTable.entrySet().iterator();
            while(iterator.hasNext()){
                Map.Entry<Path, Command> entry = iterator.next();
                Path key = entry.getKey();
                if(key.isSubpath(path)){
                    this.storageTable.remove(key);
                    iterator.remove();
                    if(this.createdDirs.contains(key))  this.createdDirs.remove(key);
                }
            }
        }
        this.commandTable.remove(path);
        this.storageTable.remove(path);
        if(this.createdDirs.contains(path)) this.createdDirs.remove(path);

        // May need to delete empty parents here!
        return b;
    }


    private Command getDirCommand(Path file) throws FileNotFoundException{
        for(Path p : this.commandTable.keySet()){
            if(p.isSubpath(file))   return this.commandTable.get(p);
        }
        throw new FileNotFoundException("Path not found");
    }

    private Storage getDirStorage(Path dir) throws FileNotFoundException{
        for(Path p : storageTable.keySet()){
            if(p.isSubpath(dir))    return storageTable.get(p);
        }
        throw new FileNotFoundException("Path not found");
    }

    @Override
    public Storage getStorage(Path file) throws FileNotFoundException
    {
        if(file==null)  throw new NullPointerException();
        Storage storage = this.storageTable.get(file);
        if(storage==null)   throw new FileNotFoundException("Path not found");
        return storage;
    }


    // check if path exists
    private boolean contains(Path path){
        for(Path p: this.storageTable.keySet()){
            if(p.isSubpath(path))   return true;
        }
        return false;
    }

    // The method register is documented in Registration.java.
    @Override
    public Path[] register(Storage client_stub, Command command_stub,
                           Path[] files)
    {
        if(client_stub==null || command_stub==null || files==null) throw new NullPointerException();


        if(this.storages.contains(client_stub) || this.commands.contains(command_stub))
            throw new IllegalStateException("Already registered");

        ArrayList<Path> toDelete = new ArrayList<>();
        for(Path f : files){
            if(this.contains(f) && !f.isRoot()){
                toDelete.add(f);
            }else{
                storageTable.put(f, client_stub);
                commandTable.put(f, command_stub);
            }
        }
        this.storages.add(client_stub);
        this.commands.add(command_stub);
        Path[] ret = toDelete.toArray(new Path[0]);
        return ret;
    }

    private class Pair{
        Path path;
        boolean exclusive;

        private Pair(Path p, boolean b){
            path = p;
            exclusive = b;
        }

        @Override
        public boolean equals(Object obj) {
            if(obj == null) return false;
            if(obj instanceof Pair){
                Pair o = (Pair) obj;
                return path.equals(o.path) && exclusive == o.exclusive;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }
    }

}
