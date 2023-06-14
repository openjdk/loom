README

This is a kinda short description of the problems and ideas I've had around Java Object Monitors.

From the start it looked like it would be pretty easy to change monitorenter/monitorexit into a call.
I made an abstraction in doCall.cpp for doCall() so that it doesn't rely on finding a bytecode that it 
can make sense of. This is called from a new shared_call_lock, shared_call_unlock which are called instead
of the old shared_lock/shared_unlock.

One of the problems with this approach is the JVM state. monitorenter and monitorexit doesn't currently make
C2 think that an exception could be thrown. Calls do however, so the JVM state needs to somehow take that into
consideration. And handle the exceptions correctly. This has turned out a bit problematic for me, I've tried
a couple of different approaches. Make C2 swallow the exception, deopt on exception, etc...

Swallowing/ignoring the exception is probably not the way forward. It was more an experiment in seeing if it could
be made to work. Most of the time exceptions shouldn't be thrown from the calls anyway.

I made a change to ciTypeFlow to make sure that monitorenter/monitorexit now creates an extra block for the exception path
as well.

Deopt on exception is a bit tricky. I think it could be made to work but exactly how it would work is a bit problematic.
I see two approaches. You can either deopt and pretend that we never really did execute the monitorenter and have the Interpreter
execute it again.... this is probably wrong in so many ways, but would make the life of the compiler way easier.
You can find the right exception handler for the exception and try to setup the exception oop and such and then deopt.
This should work for most cases I think. Where it gets problematic is:

public synchronized void foo() {
}

There is no monitorenter / monitorexit bytecode in these methods. Instead we generate the code for monitorenter at the entry, this is
still OK. For exit it's a bit worse. Parse::do_exits handles this case by walking through each exit (the normal return an the exits that
deopt...) and generate the code for a monitorexit. This code can not throw an exception as that would be a new exit... so a deopt here
probably needs to be a bit special.

Then we have frame ids. The interpreter has them to make sure they unlock the right things for JVMTI events. I don't think the compiler
needs to have them, however deopt causes a problem here. We go from having locks with no frame id to locks that need a frame id.
In the current prototype the frame id is RBP (base pointer/link) in the Interpreter. It should be possible on deopt to poke into the
java data structure and make a frame id for the locks that now needs one.

Robbin has more info on this and promised to help with the updating of java data structure.

This is also the reason why the current code has a special monitorEnter for compiler.

The current code doesn't work, but it might provide some pointers into what might work and will not work.
