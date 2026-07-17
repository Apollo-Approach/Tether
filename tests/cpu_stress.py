import sys
import multiprocessing
import time

def spin():
    # Keep CPU busy
    while True:
        pass

def main():
    duration = 60
    if len(sys.argv) > 1:
        try:
            duration = float(sys.argv[1])
        except ValueError:
            pass
            
    cores = multiprocessing.cpu_count()
    print(f"Spawning {cores} CPU-spinning processes for {duration} seconds...", flush=True)
    processes = []
    for _ in range(cores):
        p = multiprocessing.Process(target=spin)
        p.daemon = True
        p.start()
        processes.append(p)
        
    try:
        time.sleep(duration)
    except KeyboardInterrupt:
        pass
    finally:
        print("Stopping stress...", flush=True)
        for p in processes:
            p.terminate()
            p.join()

if __name__ == "__main__":
    main()
