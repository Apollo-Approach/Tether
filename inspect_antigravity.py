import win32gui

def enum_cb(hwnd, results):
    if win32gui.IsWindowVisible(hwnd):
        title = win32gui.GetWindowText(hwnd)
        if 'antigravity' in title.lower():
            print(f'Found: {title}')
            print(f'  HWND: {hwnd}')
            print(f'  Class: {win32gui.GetClassName(hwnd)}')
            
            child_hwnds = []
            def enum_child(child, _):
                child_hwnds.append(child)
            win32gui.EnumChildWindows(hwnd, enum_child, None)
            
            if child_hwnds:
                print(f'  Child Windows:')
                for child in child_hwnds:
                    print(f'    - {child} [{win32gui.GetClassName(child)}]')
            else:
                print('  No child windows')

print('Searching for Antigravity window...')
win32gui.EnumWindows(enum_cb, None)

