import win32gui
with open(r'C:\Development\Monolith\windows.txt', 'w', encoding='utf-8') as f:
    def enum_cb(hwnd, _):
        if win32gui.IsWindowVisible(hwnd):
            title = win32gui.GetWindowText(hwnd)
            classname = win32gui.GetClassName(hwnd)
            if title or classname:
                f.write(f'{hwnd}: [{classname}] {title}\n')
                def enum_child(child, _):
                    ctitle = win32gui.GetWindowText(child)
                    cclass = win32gui.GetClassName(child)
                    f.write(f'  -> {child}: [{cclass}] {ctitle}\n')
                win32gui.EnumChildWindows(hwnd, enum_child, None)
    print('Dumping windows...')
    win32gui.EnumWindows(enum_cb, None)
print('Done.')
