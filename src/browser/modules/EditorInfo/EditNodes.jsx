/*
 * This program depicts the behaviour of the edit drawer that displays
 * and edits the properties selected from the canvas. As this is used
 * for handling all the rendering and used as component in EditorInfo.
 */
import React, { Component } from 'react'
import {
  Drawer,
  DrawerBody,
  DrawerHeader,
  DrawerSection,
  DrawerSectionBody,
  DrawerSubHeader
} from 'browser-components/drawer'
import EditProperties from './EditProperties'
import * as _ from 'lodash'

export class EditNodes extends Component {
  /**
   * this method toggles the edit button
   *
   */
  handleEdit = () => {
    this.props.handleEdit()
  }

  /**
   * method for editing selected item
   * function setEditSelectedItem() as props to parent
   * componet
   *  */

  setEditSelectedItem = () => {
    this.props.setEditSelectedItem()
  }

  /**
   * Handles the changes when edited and change the state by invoking
   * parent function setParentItemState,
   * handleChange function has two params "key" and "e" where key is the index of
   * properties key and e is the event for the handlechange
   * */

  handleChange = (key, e) => {
    let newProperties = _.cloneDeep(this.props.item.item._fields[0])
    for (const i in newProperties) {
      if (key in newProperties[i]) {
        let obj = { [key]: e.target.value }
        _.assign(newProperties[i], obj)
      }
    }
    this.props.setParentItemState(newProperties)
  }

  render () {
    let content = null
    if (
      this.props.properties_state_data.neo4jItem &&
      this.props.properties_state_data.selectedItem.type !== 'canvas'
    ) {
      content = (
        <div>
          {`${this.props.properties_state_data.selectedItem.type.toUpperCase()}`}
          <hr />
          <ul>
            <li>
              Type : {`${this.props.properties_state_data.selectedItem.type}`}
            </li>
            <hr />
            <li>
              Properties :{' '}
              <EditProperties
                checkpropertiesEdited={this.props.checkpropertiesEdited}
                itemProperties={this.props.item.item._fields[0].properties}
                item={this.props.item}
                handleChange={this.handleChange}
                disabled={this.props.item.disabled}
              />
            </li>
          </ul>
          <hr />

          <div
            data-testid='sidebarMetaItem'
            className='styled__chip-sc-1srdf8s-0 styled__StyledLabel-sc-1srdf8s-1 eGKpnH'
            onClick={() => {
              this.handleEdit()
            }}
          >
            {this.props.item.disabled ? 'Edit' : 'Done'}
          </div>
          <div
            data-testid='sidebarMetaItem'
            className='styled__chip-sc-1srdf8s-0 styled__StyledLabel-sc-1srdf8s-1 eGKpnH'
            onClick={this.setEditSelectedItem}
          >
            update
          </div>
        </div>
      )
    }
    return (
      <Drawer id='db-drawer'>
        <DrawerHeader>Editor</DrawerHeader>
        <DrawerBody>
          <DrawerSection>
            <DrawerSectionBody>
              <DrawerSubHeader>{content}</DrawerSubHeader>
            </DrawerSectionBody>
          </DrawerSection>
        </DrawerBody>
      </Drawer>
    )
  }
}
